/*
* (C) Copyright 2020 Nuxeo (http://nuxeo.com/) and others.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*     http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*
* Contributors:
*     Abdoul BA <aba@nuxeo.com>
*/

/* Using a version specifier, such as branch, tag, etc */
@Library('nuxeo-napps-tools@0.0.4') _

def appName = 'nuxeo-coldstorage'
def repositoryUrl = 'https://github.com/nuxeo/nuxeo-coldstorage/'

def runBackEndUnitTests() {
  return {
    stage('BackEnd') {
      container('maven') {
        script {
          gitHubBuildStatus.set('utests/backend')
          try {
            echo '''
              ----------------------------------------
              Run BackEnd Unit tests
              ----------------------------------------
            '''
            sh """
              cd ${BACKEND_FOLDER}
              mvn ${MAVEN_ARGS} -V -T0.8C test
            """
          } catch (err) {
            throw err
          } finally {
            junit allowEmptyResults: true, testResults: "**/target/surefire-reports/*.xml"
            gitHubBuildStatus.set('utests/backend')
          }
        }
      }
    }
  }
}

def runFrontEndUnitTests() {
  return {
    stage('FrontEnd') {
      container('maven') {
        script {
          echo '''
            ----------------------------------------
            Run FrontEnd Unit tests
            ----------------------------------------
          '''
          gitHubBuildStatus.set('utests/frontend')
          try {
            withEnv(["SAUCE_USERNAME=", "SAUCE_ACCESS_KEY="]) {
              sh """
                cd ${FRONTEND_FOLDER}
                npm install
                npm run test
              """
            }
          } catch (err) {
            //Allow Fronted test to fail
            echo hudson.Functions.printThrowable(err)
          } finally {
            gitHubBuildStatus.set('utests/frontend')
          }
        }
      }
    }
  }
}

pipeline {
  agent {
    label 'builder-maven-nuxeo'
  }
  options {
    disableConcurrentBuilds()
    buildDiscarder(logRotator(daysToKeepStr: '15', numToKeepStr: '10', artifactNumToKeepStr: '5'))
  }
  environment {
    APP_NAME = "${appName}"
    BACKEND_FOLDER = "${WORKSPACE}/nuxeo-coldstorage"
    BRANCH_LC = "${BRANCH_NAME.toLowerCase().replace('.', '-')}"
    BUCKET_PREFIX = "${appName}-${BRANCH_LC}-${BUILD_NUMBER}"
    CHANGE_BRANCH = "${env.CHANGE_BRANCH != null ? env.CHANGE_BRANCH : BRANCH_NAME}"
    CHANGE_TARGET = "${env.CHANGE_TARGET != null ? env.CHANGE_TARGET : BRANCH_NAME}"
    CHART_DIR = 'ci/helm/preview'
    CONNECT_PREPROD_URL = 'https://nos-preprod-connect.nuxeocloud.com/nuxeo'
    ENABLE_GITHUB_STATUS = 'true'
    FRONTEND_FOLDER = "${WORKSPACE}/nuxeo-coldstorage-web"
    JENKINS_HOME = '/root'
    MAVEN_DEBUG = '-e'
    MAVEN_OPTS = "${MAVEN_OPTS} -Xms512m -Xmx3072m"
    // To reduce the startup time, we are using a specific docker tag but Nuxeo will install all available HFs)
    NUXEO_BASE_IMAGE = 'docker-private.packages.nuxeo.com/nuxeo/nuxeo:10.10-HF44'
    ORG = 'nuxeo'
    PREVIEW_NAMESPACE = "coldstorage-${BRANCH_LC}"
    REFERENCE_BRANCH = 'master'
    IS_REFERENCE_BRANCH = "${BRANCH_NAME == REFERENCE_BRANCH}"
    SLACK_CHANNEL = "${env.DRY_RUN == 'true' ? 'infra-napps' : 'napps-notifs'}"
  }
  stages {
    stage('Set Labels') {
      steps {
        container('maven') {
          script {
            nxNapps.setLabels()
          }
        }
      }
    }
    stage('Setup') {
      steps {
        container('maven') {
          script {
            nxNapps.setup()
            env.VERSION = nxNapps.getRCVersion()
          }
        }
      }
    }
    stage('Update Version') {
      steps {
        container('maven') {
          script {
            nxNapps.updateVersion("${VERSION}")
          }
        }
      }
    }
    stage('Compile') {
      steps {
        container('maven') {
          script {
            gitHubBuildStatus.set('compile')
            nxNapps.mavenCompile()
          }
        }
      }
      post {
        always {
          script {
            gitHubBuildStatus.set('compile')
          }
        }
      }
    }
    stage('Linting') {
      steps {
        container('maven') {
          script {
            gitHubBuildStatus.set('lint')
            try {
              nxNapps.lint("${FRONTEND_FOLDER}")
            } catch (err) {
              //Allow lint to fail
              echo hudson.Functions.printThrowable(err)
            } finally {
              gitHubBuildStatus.set('lint')
            }
          }
        }
      }
      post {
        always {
          script {
            gitHubBuildStatus.set('lint')
          }
        }
      }
    }
    stage('Run Unit Tests') {
      steps {
        script {
          def stages = [:]
          stages['backend'] = runBackEndUnitTests();
          stages['frontend'] = runFrontEndUnitTests();
          parallel stages
        }
      }
    }
    stage('Package') {
      steps {
        container('maven') {
          script {
            gitHubBuildStatus.set('package')
            nxNapps.mavenPackage()
          }
        }
      }
      post {
        always {
          script {
            gitHubBuildStatus.set('package')
          }
        }
      }
    }
    stage('Build Docker Image') {
      steps {
        container('maven') {
          script {
            gitHubBuildStatus.set('docker/build')
            nxNapps.dockerBuild(
                    "${WORKSPACE}/nuxeo-coldstorage-package/target/nuxeo-coldstorage-package-*.zip",
                    "${WORKSPACE}/ci/docker","${WORKSPACE}/ci/docker/skaffold.yaml"
            )
          }
        }
      }
      post {
        always {
          script {
            gitHubBuildStatus.set('docker/build')
          }
        }
      }
    }
    stage('Buid Helm Chart') {
      steps {
        container('maven') {
          script {
            if (nxNapps.needsJsfAddon("${REFERENCE_BRANCH}")) {
              env.JSF_ENABLED = 'nuxeo-jsf-ui'
            }
            gitHubBuildStatus.set('helm/chart/build')
            nxKube.helmBuildChart("${CHART_DIR}", 'values.yaml')
          }
        }
      }
      post {
        always {
          script {
            gitHubBuildStatus.set('helm/chart/build')
          }
        }
      }
    }
    stage('Deploy ColdStorage Preview') {
      when {
        anyOf {
          not {
            branch 'PR-*'
          }
          allOf {
            branch 'PR-*'
            expression {
              return pullRequest.labels.contains('preview')
            }
          }
        }
      }
      steps {
        container('maven') {
          script {
            gitHubBuildStatus.set('helm/chart/deploy')
            nxKube.helmDeployPreview(
                    "${PREVIEW_NAMESPACE}", "${CHART_DIR}", "${repositoryUrl}", "${IS_REFERENCE_BRANCH}"
            )
          }
        }
      }
      post {
        always {
          script {
            gitHubBuildStatus.set('helm/chart/deploy')
          }
        }
      }
    }
    stage('Publish') {
      when {
        allOf {
          not {
            branch 'PR-*'
          }
          not {
            environment name: 'DRY_RUN', value: 'true'
          }
        }
      }
      environment {
        MESSAGE = "Release ${VERSION}"
        TAG = "v${VERSION}"
      }
      stages {
        stage('Git Commit and Tag') {
          steps {
            container('maven') {
              script {
                nxNapps.gitCommit("${MESSAGE}", '-a')
                nxNapps.gitTag("${TAG}", "${MESSAGE}")
              }
            }
          }
        }
        stage('Publish ColdStorage Package') {
          steps {
            container('maven') {
              script {
                gitHubBuildStatus.set('publish/package')
                echo """
                  -------------------------------------------------
                  Upload ColdStorage Package ${VERSION} to ${CONNECT_PREPROD_URL}
                  -------------------------------------------------
                """
                nxNapps.uploadPackage("${VERSION}", 'connect-preprod', "${CONNECT_PREPROD_URL}")
              }
            }
          }
          post {
            always {
              archiveArtifacts (
                allowEmptyArchive: true,
                artifacts: 'nuxeo-coldstorage-package/target/nuxeo-coldstorage-package-*.zip'
              )
              script {
                gitHubBuildStatus.set('publish/package')
              }
            }
          }
        }
        stage('Git Push') {
          steps {
            container('maven') {
              echo """
                --------------------------
                Git push ${TAG}
                --------------------------
              """
              script {
                nxNapps.gitPush("${TAG}")
              }
            }
          }
        }
      }
    }
  }
  post {
    always {
      script {
        if (!nxNapps.isPullRequest() && env.DRY_RUN != 'true') {
          // update JIRA issue
          step([$class: 'JiraIssueUpdater', issueSelector: [$class: 'DefaultIssueSelector'], scm: scm])
          currentBuild.description = "Build ${VERSION}"
        }
      }
    }
    success {
      script {
        // update Slack Channel
        String message = "${JOB_NAME} - #${BUILD_NUMBER} ${currentBuild.currentResult} (<${BUILD_URL}|Open>)"
        slackBuildStatus.set("${SLACK_CHANNEL}", "${message}", 'good')
      }
    }
    unsuccessful {
      script {
        // update Slack Channel
        String message = "${JOB_NAME} - #${BUILD_NUMBER} ${currentBuild.currentResult} (<${BUILD_URL}|Open>)"
        slackBuildStatus.set("${SLACK_CHANNEL}", "${message}", 'danger')
      }
    }
  }
}
