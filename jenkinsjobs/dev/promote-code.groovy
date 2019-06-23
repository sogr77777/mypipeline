pipeline{

    agent { label "master" }
    // options {
    //         ansiColor('xterm')
    // }

//    parameters {
//        string(name: 'FromEnv', defaultValue: 'dev', description: 'Environment where code will be copied from')
//        string(name: 'ToEnv', defaultValue: 'test', description: 'Environment where code will be copied to')
//    }

    environment {
        // Specify environments to copy from and to
        FROM_ENV = 'dev'
        TO_ENV = 'test'
        // SSH git information as well as branch
        // SSH because all pushing must be over SSH
        GIT_REPO_SSH = "https://_tuttlz@bitbucket.aws.baxter.com/scm/EIS/devopspipelineexample.git"
        GIT_CREDENTIALS_SSH  = "baxter-global-aip"
        BRANCH_NAME = "master"
        // Temp file locations for the push
        TEMP_PEM_FILE = "${WORKSPACE}/deleteme.pem"
        TEMP_SSH_FILE = "${WORKSPACE}/deleteme.sh"
        // Jenkins env var
        IS_JENKINS_MODE = "true"
        // Recipient of the notification emails
        EMAIL_RECIPIENT = 'example@baxter.com' // Multiple emails can be separated by a semi-colon
    }

    stages {
        stage("Initialization") {
            steps {
                //Cleanup Workspace
                cleanWs()
                git branch: "master", changelog: false, credentialsId: "${GIT_CREDENTIALS_SSH}", poll: false, url: "${GIT_REPO_SSH}"
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
            This does a copy from one folder to another
            dev -> test
            or
            test -> prod
            Once the copy is complete it will add/commit/push you changed back into bitbucket
        */
        stage ('Promote Code') {
            steps {
                withCredentials([sshUserPrivateKey(credentialsId: "${GIT_CREDENTIALS_SSH}", keyFileVariable: 'SSH_KEYFILE', passphraseVariable: '', usernameVariable: 'SSH_USERNAME')]) {
                    // Copy files up to new environment. Any exceptions should be here
                    sh "cp runway/${FROM_ENV}/* runway/${TO_ENV} -r"
                    
                    // Config jenkins
                    sh 'git config user.email "jenkins-automation-server@baxter.com"'
                    sh 'git config user.name "jenkins-autoamtion-server"'

                    // Add and commit changes
                    sh "git add -A"
                    sh "git commit -m \"copy code from ${FROM_ENV} to ${TO_ENV} by jenkins BUILD_NUMBER: ${BUILD_NUMBER} \""
                    
                    // Push
                    sh 'mkdir temp || true'
                    dir ('temp') {

                        // Set up temp key
                        sh "cp '${SSH_KEYFILE}' ${TEMP_PEM_FILE}"
                        sh "chmod 400 ${TEMP_PEM_FILE}"

                        // Set up temp shell script for GIT_SSH
                        sh "echo 'exec /usr/bin/ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -i ${TEMP_PEM_FILE} \"\$@\"' >> ${TEMP_SSH_FILE}"
                        sh "chmod 777 ${TEMP_SSH_FILE}"
                        
                        // Push
                        sh "export GIT_SSH=\"${TEMP_SSH_FILE}\" && git push ${GIT_REPO_SSH} ${BRANCH_NAME}"
                    }
                }
            }
            post {
                always {
                    // Always cleanup pem file and temp dir
                    sh "rm -rf ${TEMP_PEM_FILE} temp"
                    sh "rm -rf ${TEMP_SSH_FILE} temp"
                }
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