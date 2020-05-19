@Library('Utilities2') _

node('worker_node1') {

    stage('Source') {

        // Get code from our git repository

        git 'git@diyvb2:/home/git/repositories/workshop.git '

        stash includes: 'api/**, dataaccess/**, util/**, build.gradle, settings.gradle', name: 'ws-src'

    }  

    stage('Compile') {// Compile and do unit testing

        // Run gradle to execute compile and unit testing

        gbuild4 "clean compileJava -x test"

    }

    stage('Unit Test') {

        parallel (

        

            tester1: { node ('worker_node2') {

                // always run with a new workspace

                cleanWs()

                unstash 'ws-src'

	            gbuild4 ':util:test'

            }},

            tester2: { node ('worker_node3'){

                // always run with a new workspace

                cleanWs()

                unstash 'ws-src'

	            gbuild4 '-D test.single=TestExample1* :api:test'

            }},

            tester3: { node ('worker_node2'){

                // always run with a new workspace

                cleanWs()

                unstash 'ws-src'

  	            gbuild4 '-D test.single=TestExample2* :api:test'

            }},

        

            )

    }

    stage('Integration Test')

    {

        // setup and run integration testing

        // set up integration database

        sh "mysql -uadmin -padmin registry_test < registry_test.sql"

        gbuild4 'integrationTest'

    }

    stage('Analysis')

    {

        withSonarQubeEnv('Local SonarQube') {

        sh "/opt/sonar-runner/bin/sonar-runner -X -e"

        }

        

        step([$class: 'JacocoPublisher',

        execPattern:'**/**.exec',

        classPattern: '**/classes/main/com/demo/util,**/classes/main/com/demo/dao',

        sourcePattern: '**/src/main/java/com/demo/util,**/src/main/java/com/demo/dao',

        exclusionPattern: '**/*Test*.class'])



        timeout(time: 1, unit: 'HOURS') {

            def qg = waitForQualityGate()

            if (qg.status != 'OK') {

            error "Pipeline aborted due to quality gate failure: ${qg.status}"

            }

        }

    }

    

    stage('Assemble')

    {

        // assemble war file

        def workspace = env.WORKSPACE

        def setPropertiesProc = fileLoader.fromGit('jenkins/pipeline/updateGradleProperties',

        'https://github.com/brentlaster/utilities.git', 'master', null, '')

        

        setPropertiesProc.updateGradleProperties("${workspace}/gradle.properties",

        "${params.MAJOR_VERSION}",

        "${params.MINOR_VERSION}",

        "${params.PATCH_VERSION}",

        "${params.BUILD_STAGE}")

        

        gbuild4 '-x test build assemble'

    }

    

    stage ('Publish Artifacts')

    {

        def  server = Artifactory.server "LocalArtifactory"

        def artifactoryGradle = Artifactory.newGradleBuild()

        artifactoryGradle.tool = "gradle4"

        artifactoryGradle.deployer repo:'libs-snapshot-local', server: server

        artifactoryGradle.resolver repo:'remote-repos', server: server

        

        def buildInfo = Artifactory.newBuildInfo()

        buildInfo.env.capture = true

        artifactoryGradle.deployer.deployMavenDescriptors = true

        artifactoryGradle.deployer.artifactDeploymentPatterns.addExclude("*.jar")

        artifactoryGradle.usesPlugin = false 

        

        artifactoryGradle.run buildFile: 'build.gradle', tasks: 'clean artifactoryPublish', buildInfo: buildInfo

        server.publishBuildInfo buildInfo



    }

    stage('Retrieve Latest Artifact') 

    {

        def getLatestScript = libraryResource 'ws-get-latest.sh'

        sh getLatestScript

        stash includes: '*.war', name: 'latest-warfile'

    }

    

    stage('Deploy To Docker') 

    {

        node ('worker_node3') {

            git 'git@diyvb2:/home/git/repositories/roarv2-docker.git'

            unstash 'latest-warfile'

            

            sh "docker stop `docker ps -a --format '{{.Names}} \n\n'` || true"

            sh "docker rm -f  `docker ps -a --format '{{.Names}} \n\n'` || true"

            sh "docker rmi -f \$(docker images | cut -d' ' -f1 | grep roar) || true"



            def dbImage = docker.build("roar-db-image", "-f Dockerfile_roar_db_image .")

            def webImage = docker.build("roar-web-image", "--build-arg warFile=web*.war -f Dockerfile_roar_web_image .")



            def dbContainer = dbImage.run("-p 3308:3306 -e MYSQL_DATABASE='registry' -e MYSQL_ROOT_PASSWORD='root+1' -e MYSQL_USER='admin' -e MYSQL_PASSWORD='admin'")

            def webContainer = webImage.run("--link ${dbContainer.id}:mysql -p 8089:8080")

  

            sh "docker inspect --format '{{.Name}} is available at http://{{.NetworkSettings.IPAddress }}:8080/roar' \$(docker ps -q -l)" 



        }

    }

}