# FireCloud-Orchestration
FireCloud Orchestration Service

* Documentation: [https://broadinstitute.atlassian.net/wiki/display/DSDE/Silver+Team](https://broadinstitute.atlassian.net/wiki/display/DSDE/Silver+Team)
* Github Repository: [https://github.com/broadinstitute/firecloud-orchestration/](https://github.com/broadinstitute/firecloud-orchestration/)

## IntelliJ Setup
* Configure the SBT plugin
* Open this source directory with File -> Open
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
assembly
```

Execute the jar with the path to the jar and path fo the config file:
```
java -Dconfig.file=src/main/resources/application.conf -jar target/scala-2.11/FireCloud-Orchestration-assembly-0.1-9-SNAPSHOT.jar
```

## Testing

Replace the integration components of application.conf to reflect correct values.
See DevOps or any Silver team member for details. Make sure sbt is run with the correct config file option.

    methods {
      // Mock test url. 
      // Replace with Agora CI url for integration testing.
      baseUrl="http://localhost:8989/methods"
    }
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
    
