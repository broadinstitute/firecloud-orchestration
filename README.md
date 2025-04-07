# FireCloud-Orchestration
FireCloud Orchestration Service

* URL: https://firecloud-orchestration.dsde-dev.broadinstitute.org/
* Github Repository: [https://github.com/broadinstitute/firecloud-orchestration/](https://github.com/broadinstitute/firecloud-orchestration/)

## IntelliJ Setup
* IntelliJ IDEA can be downloaded here : https://www.jetbrains.com/idea/ . It is an 'Intelligent Java IDE'
* Configure the SBT plugin.  "IntelliJ IDEA" -> "Preferences" -> "Plugins" can be used to do so (download, install)
* After running IntelliJ, open this source directory with File -> Open
* In the Import Project dialog, check "Create directories for empty content roots automatically" and set your Project SDK to 17

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
127.0.0.1	local.dsde-dev.broadinstitute.org
```

### Running:

After satisfying the above requirements, execute the following command from the root of the firecloud-orchestration repo:

```sh
./config/docker-rsync-local-orch.sh
```

If Orch successfully starts up, you can now access the Orch Swagger page: https://local.dsde-dev.broadinstitute.org:10443/

## Development Notes
* We push new features to a feature-branch and make pull requests against develop.

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



## Benchmarking

Benchmarks reside in the [benchmarks](benchmarks) subdirectory,
and are accessible via an `sbt` subproject named `bench`.

Benchmarks are powered by [JMH](https://github.com/openjdk/jmh)
and integrated into sbt using the [sbt-jmh plugin](https://github.com/sbt/sbt-jmh).

To execute benchmarks:
```
sbt bench/Jmh/run
```

To see options for running benchmarks:
```
sbt "bench/Jmh/run -h"
```
Example of specifying options when running benchmarks (this example sets 3 iterations,
2 warmup iterations, and one fork):
```
sbt "bench/Jmh/run -i 3 -wi 2 -f 1"
```

Running benchmarks takes a while, especially when specifying more iterations/warmups/forks.
Of course, using more iterations/warmups/forks is also more accurate. Benchmark output is fairly verbose;
look for the final summary that will look something like:
```
[info] Benchmark                                    Mode  Cnt         Score           Error  Units
[info] TsvFormatterBenchmark.tsvSafeStringNoTab    thrpt    3  85746770.866 ± 112486692.134  ops/s
[info] TsvFormatterBenchmark.tsvSafeStringWithTab  thrpt    3  30601083.552 ±  53318975.049  ops/s
```


