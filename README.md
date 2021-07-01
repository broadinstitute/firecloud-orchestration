[![Build Status](https://travis-ci.org/broadinstitute/firecloud-orchestration.svg?branch=develop)](https://travis-ci.org/broadinstitute/firecloud-orchestration?branch=develop)
[![Coverage Status](https://coveralls.io/repos/broadinstitute/firecloud-orchestration/badge.svg?branch=develop&service=github)](https://coveralls.io/github/broadinstitute/firecloud-orchestration?branch=develop)
test 2
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

## Development Notes
* Configuration is excluded from the build package:
    - When running via sbt, start sbt with the config file `sbt -Dconfig.file=src/main/resources/application.conf` and the run command will pick up your local configuration.
    - Alternatively, add an `.sbotopts` file to the root directory of the project with the first line being `-Dconfig.file=src/main/resources/application.conf` or pointing to whichever config file you prefer to use.
    - When running via sbt/revolver (i.e. using the re-start command), you can just run in sbt normally - the config is preset for you in build.sbt.
* We push new features to a feature-branch and make pull requests against master.
* New paths to external endpoints should be added to `src/main/resources/configurations.conf`. Existing endpoint URLs are configured in `application.conf` and `test.conf`

## Building and Running

See https://github.com/broadinstitute/firecloud-develop for directions on running locally within Broad's DSDE environment.
* Local Orchestration URL: https://local.broadinstitute.org:10443/

Run the assembly task to build a fat jar:
```
sbt
> assembly
```

```
java -Dconfig.file=src/main/resources/application.conf \
  -jar $(ls target/scala-2.12/FireCloud-Orchestration-assembly-* | tail -n 1)
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
