pipeline{

    agent { label "master" }
    // options {
    //         ansiColor('xterm')
    // }

    parameters {
        string(name: 'StackType', defaultValue: 'eu', description: 'Must be one of "eu" or "na" or "la" or "et"')
        string(name: 'BuildNumber', defaultValue: '1', description: 'Jenkins build number. Can be found as part of the stack name.')
        string(name: 'Region', defaultValue: 'us-east-2', description: 'Region to delete from')
//        string(name: 'AppNameTagValue', defaultValue: 'vanilla-runway', description: 'AppName tag on the aplication')
    }

    environment {
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
        stage('Assume role to dev') {
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
            This is the blue/green swap
            This job will complete the following:
            - Find the current green stack and change the tags to blue
            - Take the stack passed in a change its tags to green
            - Based on parameter of the stack that is passed in it will pull the DNS and the ELB/ALB and make a call to infoblox to repoint the DNS to the ELB/ALB of the newly promoted stacks
        */
        stage ('Swap stack tag colors') {
            steps {
                sh '''
                    BLUE_STACKS=$(aws cloudformation describe-stacks --region $Region --query "Stacks[?Tags[?(Key=='ERPRegion' && Value=='${StackType}')]] | [?Tags[?(Key=='StackStatus' && Value=='BLUE')]] | [?Tags[?(Key=='BuildNumber' && Value=='${BuildNumber}')]] | [?StackStatus!='DELETE_COMPLETE'] | [*].StackName" | jq ".[]")
                    GREEN_STACKS=$(aws cloudformation describe-stacks --region $Region --query "Stacks[?Tags[?(Key=='ERPRegion' && Value=='${StackType}')]] | [?Tags[?(Key=='StackStatus' && Value=='GREEN')]] | [?StackStatus!='DELETE_COMPLETE'] | [*].StackName" | jq ".[]")

                    for quoted_stack in $BLUE_STACKS; do
                        stack=$(echo $quoted_stack | cut -c 2- | rev | cut -c 2- | rev)
                        echo "PROCESSING BLUE TARGET $stack"
                        DESCRIBE_STACK=$(aws cloudformation describe-stacks --stack-name $stack --query "Stacks[].{Params: Parameters, Tags: Tags[?Key!='StackStatus']}" --region $Region)
                        PARAM_OBJECT=$(echo $DESCRIBE_STACK | jq ".[0].Params")
                        TAG_OBJECT=$(echo $DESCRIBE_STACK | jq '.[0].Tags += [{"Key": "StackStatus", "Value": "GREEN"}]' | jq '.[0].Tags')
                        
                        aws cloudformation update-stack --stack-name $stack --use-previous-template --parameters "$PARAM_OBJECT" --tags "$TAG_OBJECT" --region $Region
                    done

                    for quoted_stack in $GREEN_STACKS; do
                        stack=$(echo $quoted_stack | cut -c 2- | rev | cut -c 2- | rev)
                        echo "PROCESSING GREEN TARGET $stack"
                        DESCRIBE_STACK=$(aws cloudformation describe-stacks --stack-name $stack --query "Stacks[].{Params: Parameters, Tags: Tags[?Key!='StackStatus']}" --region $Region)
                        PARAM_OBJECT=$(echo $DESCRIBE_STACK | jq ".[0].Params")
                        TAG_OBJECT=$(echo $DESCRIBE_STACK | jq '.[0].Tags += [{"Key": "StackStatus", "Value": "BLUE"}]' | jq '.[0].Tags')
                        
                        aws cloudformation update-stack --stack-name $stack --use-previous-template --parameters "$PARAM_OBJECT" --tags "$TAG_OBJECT" --region $Region
                    done

                    ## Swap DNS
                    for quoted_stack in $BLUE_STACKS; do
                        stack=$(echo $quoted_stack | cut -c 2- | rev | cut -c 2- | rev)
                        echo "PROCESSING DNS FOR $stack"
                        DNS_NAME_CSV=$(aws cloudformation describe-stacks --stack-name $stack --query "Stacks[].Parameters[?ParameterKey=='DNSName'].ParameterValue" --output text --region $Region)
                        
                        IFS=',' read -ra DNS_NAME_ARRAY <<< "${DNS_NAME_CSV}"
                        
                        for DNS_NAME in "${DNS_NAME_ARRAY[@]}"; do
                            if [ $DNS_NAME != "nodns" ]; then
                                #### REMOVE THIS COMMAND FOR PROD ####
                                DNS_NAME="green-${DNS_NAME}"

                                aws cloudformation describe-stack-resources --stack-name $stack --region eu-central-1

                                ELB_ID=$(aws cloudformation describe-stack-resources --stack-name $stack --query "StackResources[?LogicalResourceId=='ELB'].PhysicalResourceId" --output text --region $Region)
                                if [ ${ELB_ID} != "" ]; then
                                    echo "ELB Exists"
                                    LB_DNS=$(aws elb describe-load-balancers --load-balancer-names $ELB_ID --query "LoadBalancerDescriptions[].DNSName" --output text --region $Region)
                                fi

                                ALB_ID=$(aws cloudformation describe-stack-resources --stack-name $stack --query "StackResources[?LogicalResourceId=='ALB'].PhysicalResourceId" --output text --region $Region)
                                if [ ${ALB_ID} != "" ]; then
                                    echo "ALB Exists"
                                    LB_DNS=$(aws elbv2 describe-load-balancers --load-balancer-arns $ALB_ID --query "LoadBalancers[].DNSName" --output text --region $Region)
                                fi

                                PAYLOAD='{"RequestAction": "Create", "RequestType": "CNAME", "ResourceProperties": {"Cname": "'"${DNS_NAME}"'", "ResourceCname": "'"${LB_DNS}"'"}}'
                                echo $PAYLOAD
                                aws lambda invoke --function-name bax-dev-infoblox-lambda-api --invocation-type Event --region $Region --payload "$PAYLOAD" out.txt
                            fi
                        done
                        

                        
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