pipeline{

    agent { label "master" }

    parameters {
        string(name: 'AMIID', defaultValue: 'ami-401830ab', description: 'AMI to use. Or use_latest to pull from paramater store')
    }

    environment {
        // Tags
        AWS_ACCOUNT_ID = "148433214357"
        AWS_ACCOUNT_NAME ="cml"
        APPNAME_TAG = "autorabit"
        APPID_TAG = "278142"
        ENV_TAG = "dev"
        OWNER_TAG = "victor_mao@baxter.com"
        COSTCENTER_TAG = "1001700224"
        // Vars for the packer builds
        // packer builder instance SG to use
        SECURITY_GROUPS = 'sg-974d1afc'
        // packer builder subnet to launch in
        SUBNET = 'subnet-2ba8eb51'
        // region to launch packer builder in
        REGION = 'eu-central-1'
        // ami name
        AMI_NAME = "${APPNAME_TAG}-RHEL"
        // Which regions to encrypt and copy the completed AMI to
        // EBS_ENCRYPTION_KEY_US_EAST_2 = 'arn:aws:kms:us-east-2:143049391535:key/40003a3d-fec9-44df-a65a-819b0fdc1ff2'
        EBS_ENCRYPTION_KEY_EU_CENTRAL_1 = 'arn:aws:kms:eu-central-1:148433214357:key/6bfdca3f-c1ac-4d26-8555-d5a8bdbabd72'
        // Paramater store values and prefix
        SAVE_AMI_SSM_PARAM_PREFIX="/${APPNAME_TAG}/ami/linux/RHEL7.4/"
        EIS_AMI_SSM_PARAM="/eis/ami/linux/RHEL7.4/latest"
        //Runway var
        IS_JENKINS_MODE = "true"
        // Git creds
        GIT_REPO = "ssh://git@bitbucket-prod.aws.baxter.com:7999/edp/mypipeline.git"
        GIT_CREDENTIALS = "maov-ssh"
        // Recipient of the notification emails
        EMAIL_RECIPIENT = 'victor_mao@baxter.com' // Multiple emails can be separated by a semi-colon
    }

    stages {
        stage("Initialization") {
            steps {
                //Cleanup Workspace
                cleanWs()
                git branch: "master", changelog: false, credentialsId: "${GIT_CREDENTIALS}", poll: false, url: "${GIT_REPO}"
            }
            // post section to trigger email on failure
            post {
                failure {
                    script {
                        currentBuild.result = 'FAILURE'
                        notifyBuild(currentBuild.result)
                    }
                }
            }
        }

        /*
            Because the Jenkins server exists in prod we must assume a role into the environment that we want to interact with.
            This will cause all commands we run after this to be executed against that environment instead of prod.
            After assuming a role if you need to execute commands against prod again you must assume your role back into production.
            If after assumeing a role you need to assume a role into another account/environment you must assume the role back into production first since production is the only one allowed to assume roles into other accounts.
            If you needed to assume a role in dev and then test it would look something like this:
            - Assume role in dev
            - Execute dev commands
            - Assume role in prod
            - Assume role in test
            - Execute test commands
        */
        stage('Assume Role') {
            steps {
                // Set AWS Credentials
                script {
                    //env.STSRESPONSE=sh(returnStdout: true, script: "aws sts assume-role --role-arn arn:aws:iam::${AWS_ACCOUNT_ID}:role/bax-${ENV_TAG}-${AWS_ACCOUNT_NAME}-jenkins-role --role-session-name jenkins")
                    env.STSRESPONSE=sh(returnStdout: true, script: "aws sts assume-role --role-arn arn:aws:iam::${AWS_ACCOUNT_ID}:role/baxaws-${ENV_TAG}-${APPNAME_TAG}-cloudformation-service-role --role-session-name ca-jenkins")
                    env.AWS_ACCESS_KEY_ID = sh(returnStdout: true, script: "echo \$STSRESPONSE | jq -r .Credentials.AccessKeyId").trim()
                    env.AWS_SECRET_ACCESS_KEY = sh (returnStdout: true, script: "echo \$STSRESPONSE | jq -r .Credentials.SecretAccessKey").trim()
                    env.AWS_SESSION_TOKEN = sh (returnStdout: true, script: "echo \$STSRESPONSE | jq -r .Credentials.SessionToken").trim()
                }
            }
            // post section to trigger email on failure
            post {
                failure {
                    script {
                        currentBuild.result = 'FAILURE'
                        notifyBuild(currentBuild.result)
                    }
                }
            }
        }

        /*
            Trigger packer to build an AMI. 
            The setting for packer are all driven using environment variables and the files contained in the amibuilds folder at the root of this repo.
            The packer job results in an unencrypted AMI
            https://www.packer.io/
        */
        stage("Create AMI using packer") {
            steps {
                sh '''
                    if [ ${AMIID} = "use_latest" ]
                    then
                        AMIID=$(aws ssm get-parameter --name "$SECURITY_AMI_SSM_PARAM" --query "Parameter.Value" --output text --region $REGION)
                    fi

                    cd amibuilds/linux/
                    /usr/sbin/packerio/packer build -machine-readable -var "aws_ami_id=${AMIID}" packer.json

                    cat manifest.json
                    cat manifest.json | grep '"artifact_id"' | cut -d ':' -f 3 | cut -d '"' -f 1 > ami.txt
                '''
            }
            // post section to trigger email on failure
            post {
                failure {
                    script {
                        currentBuild.result = 'FAILURE'
                        notifyBuild(currentBuild.result)
                    }
                }
            }
        }

        /*
            Since packer result in an unencrypted AMI we need to copy that AMI to all regions and use the region specific keys.
            While packer could do this for us we require and unencrypted AMI in order to promote the AMI to additional environments.
            We will tag every encrypted AMI with its unencrypted parent to be used for copying.
            AMIs will be copied to every region that has an environment variables in the sytax of "EBS_ENCRYPTION_KEY_${REGION}"
            Every region will also have corresponding parameter store values that we will write to. This allows us to programatically find the correct AMI ID in every region.
        */
        stage ('Copy and encrypt AMI in all regions') {
            steps {
                sh '''
                    ## Read in variables from previous step
                    UNENCRYPTED_SHARING_IMAGE=$(cat amibuilds/linux/ami.txt)
                    AMI_NAME=$(aws ec2 describe-images --image-ids ${UNENCRYPTED_SHARING_IMAGE} --region ${REGION} --query "Images[0].Name" --output text)

                    currentYear=$(date +%Y)
                    currentMonth=$(date +%m)
                    paramString="${SAVE_AMI_SSM_PARAM_PREFIX}${currentYear}/${currentMonth}"
                    paramStringLatest="${SAVE_AMI_SSM_PARAM_PREFIX}latest"

                    ALL_AMI_STRING=''
                    for account in $(env | grep EBS_ENCRYPTION_KEY); do
                        COPY_REGION=$(echo $account | cut -d '=' -f1 | cut -c 20- | tr "_" "-" | tr '[:upper:]' '[:lower:]')
                        ENCRYPT_KEY=$(echo $account | cut -d '=' -f2)
                        echo "Copying to $COPY_REGION"

                        ## Encrypt image
                        NEW_AMIID=$(aws ec2 copy-image --name ${AMI_NAME} --source-image-id ${UNENCRYPTED_SHARING_IMAGE} --source-region $REGION --region $COPY_REGION --query "ImageId" --output text --encrypted --kms-key-id $ENCRYPT_KEY)

                        ## Add tags to new ami
                        aws ec2 create-tags --resources ${NEW_AMIID} --tags Key=BASE_AMI,Value=${UNENCRYPTED_SHARING_IMAGE} Key=BASE_REGION,Value=${COPY_REGION} --region $COPY_REGION

                        ## Create string for job naming
                        ALL_AMI_STRING="${ALL_AMI_STRING}${NEW_AMIID};"

                        ## Write to param store
                        echo "pushing $paramString (${NEW_AMIID}) to AWS Systems Manager Parameter Store in $COPY_REGION"
                        aws ssm put-parameter --name $paramString --value $NEW_AMIID --type String --region $COPY_REGION  --overwrite $true

                        echo "pushing $paramStringLatest (${NEW_AMIID}) to AWS Systems Manager Parameter Store in $COPY_REGION"
                        aws ssm put-parameter --name $paramStringLatest --value $NEW_AMIID --type String --region $COPY_REGION  --overwrite $true
                    done

                    ## Wait for copies to become available
                    for account in $(env | grep EBS_ENCRYPTION_KEY); do
                        COPY_REGION=$(echo $account | cut -d '=' -f1 | cut -c 20- | tr "_" "-" | tr '[:upper:]' '[:lower:]')
                        AMIID=$(aws ssm get-parameter --name "${paramStringLatest}" --query "Parameter.Value" --output text --region $COPY_REGION)
                        IMAGE_STATE=$(aws ec2 describe-images --image-ids $AMIID --region $COPY_REGION --query "Images[0].State" --output text)
                        while [ "${IMAGE_STATE}" != "available" ]; do
                            echo "Waiting for copy to become available..."
                            sleep 60
                            IMAGE_STATE=$(aws ec2 describe-images --image-ids $AMIID --region $COPY_REGION --query "Images[0].State" --output text)
                        done
                    done

                    echo ${ALL_AMI_STRING} > jobname.txt

                '''
                script {
                    env.FinalAMI = readFile "jobname.txt"
                    currentBuild.displayName = "$FinalAMI"
                }
            }
            // post section to trigger email on failure
            post {
                failure {
                    script {
                        currentBuild.result = 'FAILURE'
                        notifyBuild(currentBuild.result)
                    }
                }
            }
        }

        stage('Send email') {
            steps {
                script {
                    // Set build result and trigger email for successful build
                    currentBuild.result = 'SUCCESS'
                    notifyBuild(currentBuild.result)
                }
            }
        }

    }
}

// Function to  send notification email
def notifyBuild(String buildStatus = 'STARTED') {
    buildStatus =  buildStatus ?: 'SUCCESSFUL'
    emailext (
        to: env.EMAIL_RECIPIENT,
        from: 'no-reply@baxter.com',
        subject: "Jenkins: '${env.JOB_NAME} [#${env.BUILD_NUMBER}] - $buildStatus'",
        body: """
        Jenkins Job ${env.JOB_NAME} [#${env.BUILD_NUMBER}] - $buildStatus
        Check console output at ${env.BUILD_URL}
        """
    )
}