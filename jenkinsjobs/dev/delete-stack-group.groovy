pipeline{

    agent { label "master" }
    // options {
    //         ansiColor('xterm')
    // }

    parameters {
        string(name: 'StackType', defaultValue: 'eu', description: 'Must be one of "eu" or "na" or "la" or "et"')
        string(name: 'BuildNumber', defaultValue: '1', description: 'Jenkins build number. Can be found as part of the stack name.')
        string(name: 'Region', defaultValue: 'us-east-2', description: 'Region to delete from')
    }

    environment {
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
                script {
                    // Set AWS Credentials
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
            If the stack is green. Do not allow deletion.
        */
        stage ('Check for Green') {
            steps {
                sh '''
                    if [[ $(aws cloudformation describe-stacks --region $Region --query "Stacks[?Tags[?(Key=='ERPRegion' && Value=='${StackType}')]] | [?Tags[?(Key=='BuildNumber' && Value=='${BuildNumber}')]] | [].Tags[?Key=='StackStatus']" --output text | grep -c GREEN) -gt 0 ]]; then
                        echo 'Stack is green'
                        exit 1
                    fi
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
            Turning on termination protection is part of the pipeline. Must disable it before deletion
        */
        stage ('Term Protection Off') {
            steps {
                sh '''
                    for stack in $(aws cloudformation describe-stacks --region $Region --query "Stacks[?Tags[?(Key=='ERPRegion' && Value=='${StackType}')]] | [?Tags[?(Key=='BuildNumber' && Value=='${BuildNumber}')]] | [*].StackId" --output text); do
                        echo "Turning off termination protection on $stack"
                        aws cloudformation update-termination-protection --no-enable-termination-protection --stack-name $stack --region $Region
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

        stage('Delete stack') {
            steps {
                sh '''
                    for stack in $(aws cloudformation describe-stacks --region $Region --query "Stacks[?Tags[?(Key=='ERPRegion' && Value=='${StackType}')]] | [?Tags[?(Key=='BuildNumber' && Value=='${BuildNumber}')]] | [*].StackId" --output text); do
                        echo "Deleting $stack"
                        aws cloudformation delete-stack --stack-name $stack --region $Region
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