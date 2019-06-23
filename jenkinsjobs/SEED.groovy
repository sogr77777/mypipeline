// Define the groovy job definitions is here. The seed job will create the jobs below.
// Stages will generally correspond to your environments. This repo only exists in dev but yours might look like the string below
//def stages = ['dev', 'test', 'prod']
def stages = ['dev']

// The next three arrays define you jobs and will split them up into folders for you
// AMI Jobs - these will build your AMIs for you pipeline
def amibuildjobs = ['ami-pipeline-linux', 'ami-pipeline-win-2016']
// Runway jobs - these are your deployments using runway
def runwayjobs = ['runway-app', 'runway-pre-deploy-setup', 'runway-pre-deploy-resources']
// Tool jobs - these are optional but useful jenkins jobs used to interact with AWS via the CLI. They can take action or be for reporting purposes
def tooljobs = ['promote-code', 'promote-ami', 'delete-stack-group', 'promote-stack-group']

// app name sets the jenkins folder name
def appname = 'autorabit'

// Bitbucket credentials and info
def bitbucketurl = 'https://maov@bitbucket-prod.aws.baxter.com/scm/edp/mypipeline.git'
def bitbucketcreds = 'maov'
def branchname = 'master'

// Exceptions - This file will loop through all the arrays and create jobs in all the folders. This array provides exceptions (if any) to not create jobs for
def exceptions = ['prod/promote-code', 'prod/promote-ami', 'prod/promote-stack-group']


folder("${appname}") {
    displayName("${appname}")
    description("DevOps Automations Using Runway")
}

stages.each { stage ->

    folder("${appname}/${stage}") {
        displayName("${stage}")
        description('DevOps Automations Using Runway')
    }

    if ("${stage}" == 'dev') {
        folder("${appname}/${stage}/amibuilds") {
            displayName("AMI Builds")
            description('DevOps Automations Using Runway')
        }

        amibuildjobs.each { amibuilds ->
            if (exceptions.contains("${stage}/${amibuilds}".toString()) == false) {
                pipelineJob("${appname}/${stage}/amibuilds/${amibuilds}") {

                    definition {
                        cpsScm {
                            scm {
                                git {
                                    remote {
                                        url "${bitbucketurl}"
                                        credentials "${bitbucketcreds}"
                                    }
                                    branch "${branchname}"
                                }
                            }
                            scriptPath("jenkinsjobs/${stage}/${amibuilds}.groovy")
                        }
                    }

                    label("${branchname}")
                }
            }
        }
    }

    folder("${appname}/${stage}/runway") {
        displayName("runway")
        description('DevOps Automations Using Runway')
    }

    runwayjobs.each { runway ->
        if (exceptions.contains("${stage}/${runway}".toString()) == false) {
            pipelineJob("${appname}/${stage}/runway/${runway}") {

                definition {
                    cpsScm {
                        scm {
                            git {
                                remote {
                                    url "${bitbucketurl}"
                                    credentials "${bitbucketcreds}"
                                }
                                branch "${branchname}"
                            }
                        }
                        scriptPath("jenkinsjobs/${stage}/${runway}.groovy")
                    }
                }

                label("${branchname}")
            }
        }
    }

    folder("${appname}/${stage}/tools") {
        displayName("tools")
        description('DevOps Automations Using Runway')
    }

    tooljobs.each { tools ->
        if (exceptions.contains("${stage}/${tools}".toString()) == false) {
            pipelineJob("${appname}/${stage}/tools/${tools}") {

                definition {
                    cpsScm {
                        scm {
                            git {
                                remote {
                                    url "${bitbucketurl}"
                                    credentials "${bitbucketcreds}"
                                }
                                branch "${branchname}"
                            }
                        }
                        scriptPath("jenkinsjobs/${stage}/${tools}.groovy")
                    }
                }

                label("${branchname}")
            }
        }
    }
}