pipeline{

    agent { label "master" }
    // options {
    //         ansiColor('xterm')
    // }

    parameters {
        string(name: 'AMIID', defaultValue: '/example/ami/linux/RHEL7.4/latest', description: 'AMI ID to use to deploy the application. Or the parameter store location')
//        string(name: 'DeployRegions', defaultValue: 'us-east-2,eu-central-1', description: 'Regions to copy the AMI to')
//        string(name: 'AppName', defaultValue: 'vanilla-runway', description: 'AppName')
//        string(name: 'AppNameTagValue', defaultValue: 'vanilla-runway', description: 'AppName tag on the aplication')
    }

    environment {
        APP_NAME = 'example'
        REGION = 'us-east-2' //primary region
        // Account to share to
        SHARE_ACCOUNT= '203058073716' //entplat-prod='737965399985' entplat-test='203058073716'
        // Will copy to each region you supply a key for. Format is "NEW_ACCT_ENCRYPT_KEY_${REGION}". 
        // bax-test-erp-encryptkey2
        NEW_ACCT_ENCRYPT_KEY_US_EAST_2="arn:aws:kms:us-east-2:203058073716:key/4687a3d1-b9a7-46a2-b142-9c34effbab7e"
        // bax-test-erp-encryptkey
        //NEW_ACCT_ENCRYPT_KEY_US_EAST_2="arn:aws:kms:us-east-2:203058073716:key/29ef9ab1-3f7d-4255-a571-f11634a296bc"
        NEW_ACCT_ENCRYPT_KEY_EU_CENTRAL_1="arn:aws:kms:eu-central-1:203058073716:key/fcb18142-aa94-4150-a1a4-f5df9533956d"
        // Prefix for saving values to parameter store
        SAVE_AMI_SSM_PARAM_PREFIX="/${APP_NAME}/ami/linux/RHEL7.4/"
        // Recipient of the notification emails
        EMAIL_RECIPIENT = 'example@baxter.com' // Multiple emails can be separated by a semi-colon
    }

    stages {
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
        stage('Assume Role to Dev') {
            steps {
                // Set AWS Credentials
                script {
                    env.STSRESPONSE=sh(returnStdout: true, script: "aws sts assume-role --role-arn arn:aws:iam::143049391535:role/bax-dev-entplat-jenkins-role --role-session-name jenkins")
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
            Pull the latest AMI from parameter store (unless a specfic one was provided as an input) and find the non encrypted parent based on tags
        */
        stage('Get latest AMI details') {
            steps {
                sh '''
                    INPUT_AMIID=$AMIID

                    ## Get info for AMI
                    if [[ $INPUT_AMIID = *"/"* ]]; then
                        AMIID=$(aws ssm get-parameter --name "${INPUT_AMIID}" --query "Parameter.Value" --output text --region $REGION)
                    else
                        AMIID=$INPUT_AMIID
                    fi
                    AMI_NAME=$(aws ec2 describe-images --image-ids $AMIID --region $REGION --query 'Images[].Name' --output text)
                    UNENCRYPTED_SHARING_IMAGE=$(aws ec2 describe-images --image-ids $AMIID --region $REGION --query "Images[0].Tags[?Key == 'BASE_AMI'].Value" --output text)
                    echo $AMI_NAME > AMI_NAME.txt
                    echo $UNENCRYPTED_SHARING_IMAGE > UNENCRYPTED_SHARING_IMAGE.txt
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
            Share the image and associated volumes with the new accounts
        */
        stage ('Share image (and volumes) to new account (${SHARE_ACCOUNT})') {
            steps {
                sh '''
                    ## Read in variables from previous step
                    UNENCRYPTED_SHARING_IMAGE=$(cat UNENCRYPTED_SHARING_IMAGE.txt)

                    ## Share image
                    aws ec2 modify-image-attribute --image-id ${UNENCRYPTED_SHARING_IMAGE} --launch-permission '{"Add":[{"UserId":"'"${SHARE_ACCOUNT}"'"}]}' --region $REGION

                    ## Share volumes
                    for snapshot in $(aws ec2 describe-images --image-ids ${UNENCRYPTED_SHARING_IMAGE} --region $REGION --query "Images[].BlockDeviceMappings[].Ebs.SnapshotId" --output text); do
                        echo "Snapshot to share: $snapshot"
                        aws ec2 modify-snapshot-attribute --snapshot-id $snapshot --attribute createVolumePermission --operation-type add --user-ids ${SHARE_ACCOUNT} --region $REGION
                    done
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
        stage('Assume Role to ${SHARE_ACCOUNT}') {
            steps {
                // Set AWS Credentials back to prod before assuming role
                script {
                    env.STSRESPONSE=sh(returnStdout: true, script: "curl 169.254.169.254/latest/meta-data/iam/security-credentials/bax-prod-entplat-jenkins-role | jq -c .")
                    env.AWS_ACCESS_KEY_ID = sh(returnStdout: true, script: "echo \$STSRESPONSE | jq -r .AccessKeyId").trim()
                    env.AWS_SECRET_ACCESS_KEY = sh (returnStdout: true, script: "echo \$STSRESPONSE | jq -r .SecretAccessKey").trim()
                    env.AWS_SESSION_TOKEN = sh (returnStdout: true, script: "echo \$STSRESPONSE | jq -r .Token").trim()
                }
                script {
                    env.STSRESPONSE=sh(returnStdout: true, script: "aws sts assume-role --role-arn arn:aws:iam::203058073716:role/bax-test-entplat-jenkins-role --role-session-name jenkins")
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
            Due to the way that some AMI are created you must create a new AMI.
            In this step we are taking that precaution and assuming that all images will have this issue (serving the lowest common denominator)
            This step will create a server from the shared AMI and wait for the instance and system status to become healthy.
            One all the status checks are healthy it will stop the instance and create a new AMI.
            It will wait for the new AMI to become available and then it will terminate the instance we create in the start of this step.
        */
        stage ('Start stop and create image') {
            steps {
                sh '''
                    ## Read in variables from previous step
                    UNENCRYPTED_SHARING_IMAGE=$(cat UNENCRYPTED_SHARING_IMAGE.txt)

                    USE_NEXT=false
                    SUBNET_TO_USE=''
                    for subnet in $(aws ec2 describe-subnets --filters Name=state,Values=available --query "Subnets[].{\"subnet\": SubnetId,\"name\": [Tags[?Key == 'Name']][0][0].Value}" --output text --region $REGION); do
                        echo "---$subnet"
                        if [[ $USE_NEXT == true ]]; then
                            SUBNET_TO_USE=$subnet
                            break
                        fi
                        if [[ $subnet = *"priv"* ]]; then
                            USE_NEXT=true
                        fi
                    done
                    echo $SUBNET_TO_USE

                    INSTANCE_ID=$(aws ec2 run-instances --image-id ${UNENCRYPTED_SHARING_IMAGE} --count 1 --instance-type m4.large --subnet-id ${SUBNET_TO_USE} --region $REGION --query "Instances[].InstanceId" --output text)

                    INSTANCE_STATE=$(aws ec2 describe-instances --instance-ids ${INSTANCE_ID} --region ${REGION} --query "Reservations[].Instances[].State.Name" --output text)
                    while [ "${INSTANCE_STATE}" != "running" ]; do
                        echo "waiting on instance to start"
                        sleep 30
                        INSTANCE_STATE=$(aws ec2 describe-instances --instance-ids ${INSTANCE_ID} --region ${REGION} --query "Reservations[].Instances[].State.Name" --output text)
                    done

                    INSTANCE_SYSTEM_STATUS=$(aws ec2 describe-instance-status --instance-ids ${INSTANCE_ID} --region ${REGION} --query "InstanceStatuses[].SystemStatus.Status" --output text)
                    while [ "${INSTANCE_SYSTEM_STATUS}" != "ok" ]; do
                        echo "waiting on instance system status"
                        sleep 30
                        INSTANCE_SYSTEM_STATUS=$(aws ec2 describe-instance-status --instance-ids ${INSTANCE_ID} --region ${REGION} --query "InstanceStatuses[].SystemStatus.Status" --output text)
                    done

                    INSTANCE_INSTANCE_STATUS=$(aws ec2 describe-instance-status --instance-ids ${INSTANCE_ID} --region ${REGION} --query "InstanceStatuses[].InstanceStatus.Status" --output text)
                    while [ "${INSTANCE_INSTANCE_STATUS}" != "ok" ]; do
                        echo "waiting on instance instance status"
                        sleep 30
                        INSTANCE_INSTANCE_STATUS=$(aws ec2 describe-instance-status --instance-ids ${INSTANCE_ID} --region ${REGION} --query "InstanceStatuses[].InstanceStatus.Status" --output text)
                    done

                    aws ec2 stop-instances --instance-ids ${INSTANCE_ID} --region ${REGION}

                    INSTANCE_STATE=$(aws ec2 describe-instances --instance-ids ${INSTANCE_ID} --region ${REGION} --query "Reservations[].Instances[].State.Name" --output text)
                    while [ "${INSTANCE_STATE}" != "stopped" ]; do
                        echo "waiting on instance to stop"
                        sleep 30
                        INSTANCE_STATE=$(aws ec2 describe-instances --instance-ids ${INSTANCE_ID} --region ${REGION} --query "Reservations[].Instances[].State.Name" --output text)
                    done

                    TMP_AMIID=$(aws ec2 create-image --instance-id ${INSTANCE_ID} --name "TempAMI-${APP_NAME}-${BUILD_NUMBER}" --query "ImageId" --output text --region $REGION)

                    IMAGE_STATE=$(aws ec2 describe-images --image-ids $TMP_AMIID --region $REGION --query "Images[0].State" --output text)
                    while [ "${IMAGE_STATE}" != "available" ]; do
                        echo "Waiting for unencrypted copy to become available..."
                        sleep 60
                        IMAGE_STATE=$(aws ec2 describe-images --image-ids $TMP_AMIID --region $REGION --query "Images[0].State" --output text)
                        if [ "${IMAGE_STATE}" != "available" ]; then
                            echo "make sure that the AMI is done..."
                            sleep 30
                            IMAGE_STATE=$(aws ec2 describe-images --image-ids $TMP_AMIID --region $REGION --query "Images[0].State" --output text)
                        fi
                    done

                    echo $TMP_AMIID > TMP_AMIID.txt

                    aws ec2 terminate-instances --instance-ids ${INSTANCE_ID}  --region $REGION
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
            This step, similarly to when we create the AMI, is used to copy the unencrypted AMI out to other regions.
            It will copy to every region it is given a NEW_ACCT_ENCRYPT_KEY_${REGION} environment variable for.
            These environment variables need to be set to corespond to the encryption keys in those regions.
            This step will also write the parameter store values out for the region once it does the copy.
            This step ends by waiting for the encrypted/copied AMIs to become ready to use.
        */
        stage ('Encrypt image in new account (${SHARE_ACCOUNT})') {
            steps {
                sh '''
                    ## Read in variables from previous step
                    UNENCRYPTED_SHARING_IMAGE=$(cat UNENCRYPTED_SHARING_IMAGE.txt)
                    TMP_AMIID=$(cat TMP_AMIID.txt)
                    AMI_NAME=$(cat AMI_NAME.txt)
                    
                    currentYear=$(date +%Y)
                    currentMonth=$(date +%m)
                    paramString="${SAVE_AMI_SSM_PARAM_PREFIX}${currentYear}/${currentMonth}"
                    paramStringLatest="${SAVE_AMI_SSM_PARAM_PREFIX}latest"

                    ALL_AMI_STRING=''
                    for account in $(env | grep NEW_ACCT_ENCRYPT_KEY); do
                        COPY_REGION=$(echo $account | cut -d '=' -f1 | cut -c 22- | tr "_" "-" | tr '[:upper:]' '[:lower:]')
                        ENCRYPT_KEY=$(echo $account | cut -d '=' -f2)
                        echo "Copying to $COPY_REGION"

                        ## Encrypt image
                        NEW_AMIID=$(aws ec2 copy-image --name ${AMI_NAME} --source-image-id ${TMP_AMIID} --source-region $REGION --region $COPY_REGION --query "ImageId" --output text --encrypted --kms-key-id $ENCRYPT_KEY)

                        ## Add tags to new ami
                        aws ec2 create-tags --resources ${NEW_AMIID} --tags Key=BASE_AMI,Value=${UNENCRYPTED_SHARING_IMAGE} Key=BASE_REGION,Value=${REGION} --region $COPY_REGION

                        ## Create string for job naming
                        ALL_AMI_STRING="${ALL_AMI_STRING}${NEW_AMIID};"

                        ## Write to param store
                        echo "pushing $paramString (${NEW_AMIID}) to AWS Systems Manager Parameter Store in $COPY_REGION"
                        aws ssm put-parameter --name $paramString --value $NEW_AMIID --type String --region $COPY_REGION  --overwrite $true

                        echo "pushing $paramStringLatest (${NEW_AMIID}) to AWS Systems Manager Parameter Store in $COPY_REGION"
                        aws ssm put-parameter --name $paramStringLatest --value $NEW_AMIID --type String --region $COPY_REGION  --overwrite $true
                    done

                    ## Wait for copies to become available
                    for account in $(env | grep NEW_ACCT_ENCRYPT_KEY); do
                        COPY_REGION=$(echo $account | cut -d '=' -f1 | cut -c 22- | tr "_" "-" | tr '[:upper:]' '[:lower:]')
                        AMIID=$(aws ssm get-parameter --name "${paramStringLatest}" --query "Parameter.Value" --output text --region $COPY_REGION)
                        IMAGE_STATE=$(aws ec2 describe-images --image-ids $AMIID --region $COPY_REGION --query "Images[0].State" --output text)
                        while [ "${IMAGE_STATE}" != "available" ]; do
                            echo "Waiting for encrypted copy to become available in $COPY_REGION..."
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

        /*
            Remove the temporary AMI that was created earlier in the job.
            Anytime we want to share we will always share from the original account (most likely dev)
        */
        stage ('Delete tmp image') {
            steps {
                sh '''
                    ## Read in variables from previous step
                    TMP_AMIID=$(cat TMP_AMIID.txt)

                    ## Share image
                    aws ec2 deregister-image --image-id ${TMP_AMIID} --region $REGION
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