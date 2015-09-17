# FireCloud-Orchestration
FireCloud Orchestration Service

* URL: https://firecloud.dsde-dev.broadinstitute.org/service/
* Documentation: [https://broadinstitute.atlassian.net/wiki/display/DSDE/Silver+Team](https://broadinstitute.atlassian.net/wiki/display/DSDE/Silver+Team)
* Github Repository: [https://github.com/broadinstitute/firecloud-orchestration/](https://github.com/broadinstitute/firecloud-orchestration/)

## IntelliJ Setup
* IntelliJ IDEA can be downloaded here : https://www.jetbrains.com/idea/ . It is an 'Intelligent Java IDE'
* Configure the SBT plugin.  "IntelliJ IDEA" -> "Preferences" -> "Plugins" can be used to do so (download, install)
* After running IntelliJ, open this source directory with File -> Open
* In the Import Project dialog, check "Create directories for empty content roots automatically" and set your Project SDK to 1.8

## Plugins
* spray-routing
* spray-json
* sbt-assembly
* sbt-revolver
* spray-swagger
* mock-server

## Development Notes
* Configuration is excluded from the build package:
    - When running via sbt, start sbt with the config file `sbt -Dconfig.file=src/main/resources/application.conf` and the run command will pick up your local configuration.
    - Alternatively, add an `.sbotopts` file to the root directory of the project with the first line being `-Dconfig.file=src/main/resources/application.conf` or pointing to whichever config file you prefer to use.
    - When running via sbt/revolver (i.e. using the re-start command), you can just run in sbt normally - the config is preset for you in build.sbt.
* We push new features to a feature-branch and make pull requests against master.
* New paths to external endpoints should be added to `src/main/resources/configurations.conf`. Existing endpoint URLs are configured in `application.conf` and `test.conf`

## Building and Running

Run the assembly task to build a fat jar:
```
sbt
> assembly
```

Execute the jar with the path to the jar and path of the desired config file:

```
java -Dconfig.file=src/main/resources/application.conf \
  -jar $(ls target/scala-2.11/FireCloud-Orchestration-assembly-* | tail -n 1)
```

For development, you can have sbt recompile and restart the server whenever a file changes on disk:
```
sbt
> ~ reStart
```

## Testing

```
AGORA_URL_ROOT='http://localhost:8989' RAWLS_URL_ROOT='http://localhost:8990' sbt test
```

## Debugging

Remote debugging is enabled for firecloud-orchestration on port 5051.

## Production Deployment

Build the docker container:
```
docker build -t firecloud-orch .
```

Run the container:
```
docker run --name orch \
  -e RAWLS_URL_ROOT='https://rawls.dsde-dev.broadinstitute.org' \
  -e AGORA_URL_ROOT='https://agora.dsde-dev.broadinstitute.org' \
  firecloud-orch
```