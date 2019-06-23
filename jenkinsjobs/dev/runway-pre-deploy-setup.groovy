pipeline{

    agent { label "master" }

    environment {
        // Parent folder and deploy env will often be the same
        PARENT_FOLDER           = 'dev'
        DEPLOY_ENVIRONMENT      = 'dev'
        // Module to deploy
        MODULE_NAME             = 'pre-deploy-setup'
        IS_JENKINS_MODE         = "true"
        CI                      = "true"
        GIT_REPO                = "https://_tuttlz@bitbucket.aws.baxter.com/scm/eis/devopspipelineexample.git"
        GIT_CREDENTIALS         = "bit-bucket"
        // Recipient of the notification emails
        EMAIL_RECIPIENT = 'example@baxter.com' // Multiple emails can be separated by a semi-colon
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
            cfn-nag is an open source tool that allows us to do some basic checking of the cloudformation templates before submitting them to Amazon.
            Some example of things we are checking for can be found here -> https://github.com/stelligent/cfn_nag/tree/master/spec/custom_rules
            We also use some custom bash expressions to check for the required tags
            If this step fails it should produce a usable error to tell you what you are missing or what is wrong with your template.
            THIS SECTION OF THE TEMPLATE SHOULD NOT BE EDITED
            https://github.com/stelligent/cfn_nag
        */
        stage ('cfn-nag') {
            steps {
                sh '''
                    cd runway/${PARENT_FOLDER}/${MODULE_NAME}
                    cfnDir="/usr/local/bin"

                    ## Find and scan all templates directories
                    for dir in `find $(pwd) -type d -name "templates" -print`; do
                        ## echo "scanning directory $dir"
                        ## ${cfnDir}/cfn_nag_scan --input-path $dir --output-format json
                        ## Loop through files and check for tags.
                        ## Does not ensure tag on every resource but will make sure it occurs atleast once
                        for cfnFile in `find $dir -name "*.*" -print`; do
                            echo "checking $cfnFile for tags"
                            if [[ `cat $cfnFile | grep -c "Name"` = 0 ]]; then
                                echo "Could not find Name tag in template $cfnFile"
                                exit 1
                            fi
                            if [[ `cat $cfnFile | grep -c "Env"` = 0 ]]; then
                                echo "Could not find Env tag in template $cfnFile"
                                exit 1
                            fi
                            if [[ `cat $cfnFile | grep -c "Appname"` = 0 ]]; then
                                echo "Could not find Appname tag in template $cfnFile"
                                exit 1
                            fi
                            if [[ `cat $cfnFile | grep -c "Appid"` = 0 ]]; then
                                echo "Could not find Appid tag in template $cfnFile"
                                exit 1
                            fi
                            if [[ `cat $cfnFile | grep -c "Owner"` = 0 ]]; then
                                echo "Could not find Owner tag in template $cfnFile"
                                exit 1
                            fi
                            if [[ `cat $cfnFile | grep -c "Costcenter"` = 0 ]]; then
                                echo "Could not find Costcenter tag in template $cfnFile"
                                exit 1
                            fi
                            echo "checking $cfnFile for 0.0.0.0/0"
                            if [[ `cat $cfnFile | grep -c "0.0.0.0/0"` > 0 ]]; then
                                echo 'Found use of 0.0.0.0/0 in $cfnFile'
                                exit 1
                            fi
                        done
                    done

                    # Find and scan all .env files
                    for envFile in `find $(pwd)  -name "*.env" -print`; do
                        echo "scanning env file $envFile"
                        if [[ `cat $envFile | grep -c "0.0.0.0/0"` > 0 ]]; then
                            echo 'Found use of 0.0.0.0/0 in $envFile'
                            exit 1
                        fi
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
        stage('Assume Role') {
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
            https://github.com/onicagroup/runway/
            Runway is a wrapper for cloudformation.
            This wrapper allows us to use this standard directory structure to deploy infrastructure.
            It also allows use to use environment files to extract away the differences between environments and have a standard deployment to every environment
            Runway has a few commands:
            - runway test (aka runway preflight) - execute this in your environment to catch errors; if it exits 0, you're ready for...
            - runway plan (aka runway taxi) - this optional step will show the diff/plan of what will be changed. With a satisfactory plan you can...
            - runway deploy (aka runway takeoff) - if running interactively, you can choose which deployment to run; otherwise (i.e. on your CI system) each deployment will be run in sequence.
        */
        stage ('Preflight Check') {
            steps {
                sh 'cd runway/${PARENT_FOLDER}/${MODULE_NAME} && runway preflight'
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
            https://github.com/onicagroup/runway/
            Runway is a wrapper for cloudformation.
            This wrapper allows us to use this standard directory structure to deploy infrastructure.
            It also allows use to use environment files to extract away the differences between environments and have a standard deployment to every environment
            Runway has a few commands:
            - runway test (aka runway preflight) - execute this in your environment to catch errors; if it exits 0, you're ready for...
            - runway plan (aka runway taxi) - this optional step will show the diff/plan of what will be changed. With a satisfactory plan you can...
            - runway deploy (aka runway takeoff) - if running interactively, you can choose which deployment to run; otherwise (i.e. on your CI system) each deployment will be run in sequence.
        */
        stage ('Deploy') {
            steps {
                //sh "cd runway/${PARENT_FOLDER}/${MODULE_NAME} && runway taxi"
                sh "cd runway/${PARENT_FOLDER}/${MODULE_NAME} && runway takeoff"
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