Quickstart: running integration tests locally on Mac/Docker 

## Running in docker

See [firecloud-automated-testing](https://github.com/broadinstitute/firecloud-automated-testing).


## Running tests from your local machine

### Set Up

Render configs:
```bash
./render-local-env.sh [branch of firecloud-automated-testing] [vault token] [env] [service root]
```

**Arguments:** (arguments are positional)

* branch of firecloud-automated-testing
    * Configs branch; defaults to `master`
* Vault auth token
	* Defaults to reading it from the .vault-token via `$(cat ~/.vault-token)`.
* env
	* Environment of your FiaB; defaults to `dev`
* service root
    * the name of your local clone of firecloud-orchestration if not `firecloud-orchestration`

##### Testing against a live environment
Be careful when testing against a persistent environment (dev, alpha, staging, etc) - be sure
your test runs do not interrupt any other work happening in those environments, and be sure your
test runs do not leave cruft behind.

To render configs for a live environment:
1. Manually change `render-local-env.sh` line 15 from `FC_INSTANCE=fiab` to `FC_INSTANCE=live`
2. render configs with `./render-local-env.sh master $(cat ~/.vault-token) alpha`, replacing `alpha` with your target env

TODO: update `render-local-env.sh` so it doesn't require manual code changes

##### Testing against your own FiaB

TODO: cWDS-related tests (`AsyncImportSpec`) are nonfunctional against FiaBs without additional manual configuration

To render configs to test against your own FiaB, accept all defaults: `./render-local-env.sh`

##### Using a local UI

Set `LOCAL_UI=true` before calling `render-local-env.sh`:
```bash
LOCAL_UI=true ./render-local-env.sh
```

### Run tests

#### From IntelliJ
To run tests from IntelliJ, it is recommended to create a separate project for the `automation` folder instead of attempting to run tests from the `firecloud-orchestration` root folder.
To do this, go to `File` -> `New` -> `Project from Existing Sources...` and navigate __into__ the `automation` folder, click `Open` to accept, and then import from SBT.

Then, you may need to tweak your ScalaTest run configurations. In IntelliJ, go to `Run` > `Edit Configurations...`, select `ScalaTest` under `Defaults`, and:

* make sure that there is a `Build` task configured to run before launch.
* you may also need to check the `Use sbt` checkbox

Now, simply open the test spec, right-click on the class name or a specific test string, and select `Run` or `Debug` as needed.
A good one to start with is `OrchestrationApiSpec` to make sure your base configuration is correct. All test code lives in `automation/src/test/scala`.

#### From the command line

To run all tests:

```bash
sbt test
```

To run a single suite:

```bash
sbt "testOnly *OrchestrationApiSpec"
```

To run a single test within a suite:

```bash
# matches test via substring
sbt "testOnly *OrchestrationApiSpec -- -z \"not find a non-existent billing project\""
```

For more information see [SBT's documentation](https://www.scala-sbt.org/1.x/docs/Testing.html) and [ScalaTest's User Guide](https://www.scalatest.org/user_guide).

