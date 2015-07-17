# FireCloud-Orchestration
FireCloud Orchestration Service

* URL: https://firecloud-orchestration-ci.broadinstitute.org/swagger/index.html?url=/api-docs
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
    - When running via sbt, start sbt with the config file ```sbt -Dconfig.file=src/main/resources/application.conf``` and the run command will pick up your local configuration.
    - Alternatively, add an ```.sbotopts``` file to the root directory of the project with the first line being ```-Dconfig.file=src/main/resources/application.conf``` or pointing to whichever config file you prefer to use.
    - When running via sbt/revolver (i.e. using the re-start command), you can just run in sbt normally - the config is preset for you in build.sbt.
* We push new features to a feature-branch and make pull requests against master.

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

Replace the openam components of appropriate config file to reflect correct values. 
See DevOps or any Silver team member for details. Make sure sbt is run with the correct config file option.

There are two example configuration files that can be used. For integration testing, 
start sbt with `src/main/resources/application.conf`. For mock testing, use `src/test/resources/test.conf`. 
`application.conf` has the urls for public services. `test.conf` has urls for local mock servers.

    openam {
	  deploymentUri = "...replace..."
	  realm = "...replace..."
	  username = "...replace..."
	  password = "...replace..."
	  commonName = "...replace..."
	  authIndex {
	    type = "module"
	    value = "DataStore"
	  }
    }  

## Debugging

Remote debugging is enabled for firecloud-orchestration on port 5051.