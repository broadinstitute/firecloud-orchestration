[![Build Status](https://travis-ci.org/broadinstitute/firecloud-orchestration.svg?branch=develop)](https://travis-ci.org/broadinstitute/firecloud-orchestration?branch=develop)
[![Coverage Status](https://coveralls.io/repos/broadinstitute/firecloud-orchestration/badge.svg?branch=develop&service=github)](https://coveralls.io/github/broadinstitute/firecloud-orchestration?branch=develop)

# FireCloud-Orchestration
FireCloud Orchestration Service

* URL: https://firecloud-orchestration.dsde-dev.broadinstitute.org/
* Documentation: [https://broadinstitute.atlassian.net/wiki/display/DSDE/Silver+Team](https://broadinstitute.atlassian.net/wiki/display/DSDE/Silver+Team)
* Github Repository: [https://github.com/broadinstitute/firecloud-orchestration/](https://github.com/broadinstitute/firecloud-orchestration/)

## IntelliJ Setup
* IntelliJ IDEA can be downloaded here : https://www.jetbrains.com/idea/ . It is an 'Intelligent Java IDE'
* Configure the SBT plugin.  "IntelliJ IDEA" -> "Preferences" -> "Plugins" can be used to do so (download, install)
* After running IntelliJ, open this source directory with File -> Open
* In the Import Project dialog, check "Create directories for empty content roots automatically" and set your Project SDK to 11

## Plugins
* spray-routing
* spray-json
* sbt-assembly
* sbt-revolver
* mock-server

## Running Locally

### Requirements:

* [Docker Desktop](https://www.docker.com/products/docker-desktop) (4GB+, 8GB recommended)
* Broad internal internet connection (or VPN, non-split recommended)
* Render the local configuration files. From the root of this repo, run:
```sh
./local-dev/bin/render
```

*  The `/etc/hosts` file on your machine must contain this entry (for calling Orch endpoints):
```sh
127.0.0.1	local.broadinstitute.org
```

### Running:

After satisfying the above requirements, execute the following command from the root of the firecloud-orchestration repo:

```sh
./config/docker-rsync-local-orch.sh
```

If Orch successfully starts up, you can now access the Orch Swagger page: https://local.broadinstitute.org:10443/

## Development Notes
* We push new features to a feature-branch and make pull requests against master.

## Building

Run the assembly task to build a fat jar:
```
sbt
> assembly
```

For development, you can have sbt recompile and restart the server whenever a file changes on disk:
```
sbt
> ~ reStart
```

## Testing

```
sbt test
```

## Integration Testing

Start an Elasticsearch server in a docker container:
```sh
./docker/run-es.sh start
```

Run integration tests. You can re-run tests multiple times against the same running Elasticsearch container.
```sh
sbt it:test
```

Stop the Elasticsearch server once you are done with your tests:
```sh
./docker/run-es.sh stop
```
If you find that `./docker/run-es.sh start` fails silently, fails mysteriously, or fails while attempting
to pre-populate data, you may be running into RAM limits where the Elasticsearh image cannot get enough memory
to run. Try increasing [Docker's RAM allocation](https://docs.docker.com/docker-for-mac/#resources).

## Docker

To build the orch jar with docker, and then build the orch docker image, run:
```
./script/build.sh jar -d build 
```

## Debugging

Remote debugging is enabled for firecloud-orchestration on port 5051.
In order to debug in Intellij:
1. first modify the `build.sbt` file as follows:
   1. Comment out the `Revolver.enableDebugging` [line](https://github.com/broadinstitute/firecloud-orchestration/blob/f0873d444114013ec9612d28c2ccb17bc85f05d2/build.sbt#L11)
   2. Replace the `reStart / javaOptions` [line](https://github.com/broadinstitute/firecloud-orchestration/blob/f0873d444114013ec9612d28c2ccb17bc85f05d2/build.sbt#L17) with `reStart / javaOptions ++= sys.env.getOrElse("JAVA_OPTS", "")
      .concat(" -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5051")
      .split(" ")
      .toList`

    That section of the `build.sbt` file should now look like this:
    ```aidl
    //Revolver.enableDebugging(port = 5051, suspend = false)
    
    // When JAVA_OPTS are specified in the environment, they are usually meant for the application
    // itself rather than sbt, but they are not passed by default to the application, which is a forked
    // process. This passes them through to the "re-start" command, which is probably what a developer
    // would normally expect.
    reStart / javaOptions ++= sys.env.getOrElse("JAVA_OPTS", "")
    .concat(" -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5051")
    .split(" ")
    .toList
    ```
2. In Intellij, go to Run -> Edit Configurations
3. Choose "Add new Configuration" (the + sign)
4. Select "Remote JVM Debug" and set the following configuration:
   1. Debugger mode: Attach to remote JVM
   2. Host: localhost
   3. Port: 5051
5. Run the local firecloud docker with `./config/docker-rsync-local-orch.sh` from the root directory
6. In Intellij, choose your debug configuration and run 'debug'.

