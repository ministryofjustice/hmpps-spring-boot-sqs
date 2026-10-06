# Running Locally

A test application found in module `test-app` applies this library and is used for both functional tests and as an
example application you can spin up. There is a reactive version too called `test-app-reactive`.

## Running the Unit Tests

To run only the unit tests found in the `hmpps-sqs-spring-boot-autoconfigure` module use command:

`./gradlew hmpps-sqs-spring-boot-autoconfigure:test`

### Running All Tests

Use the following command to run all tests, with the functional tests running against a Testcontainers LocalStack
instance:

`./gradlew test`

Note that Testcontainers only starts if LocalStack is not running on port 4566.

### Running the Functional Tests

From the root of the project run the following command to test only the test-app tests against a Testcontainers
LocalStack instance:

`./gradlew test-app:test`

## With Standalone LocalStack

If you are developing in this library then starting and stopping a Testcontainers LocalStack instance for every test run
quickly becomes tedious.

Use this command to start a standalone LocalStack instance:

`docker compose -f docker compose-test.yml up localstack`

The standalone LocalStack instance does not need stopping and starting between test runs.

## Running the test-app

Running `test-app` or `test-app-reactive` locally can be useful for debugging features provided by this library.

Start localstack with command:

`docker compose -f docker compose-test.yml up localstack`

Then run the `test-app` in your IDE from main class `HmppsTemplateKotlin` using Spring profiles `localstack`.

Some messages to process can be found in the root of this project. These can be sent to the inbound topic / queue by
running the scripts.

### AWS CLI

Sending the test messages requires the AWS CLI to be installed. On macs this can be installed with
`brew install awscli`.

It can be configured by running `aws configure`. For running locally the key id and access key can both be set to
any value, so `dummy` will suffice. The region should be set to `eu-west-2`.

## Running with Application Insights

There are interceptors in this library that create spans for messages received and also published. It is then useful to
be able to test these spans are being created and sent to Application Insights correctly.

The `test-app` can be configured to send the messages to Application Insights. To do so set the VM option:

```
-javaagent:/<path to sqs directory>/hmpps-spring-boot-sqs/test-app/build/libs/applicationinsights-agent-<agent version>.jar
```

The Application Insights agent jar needs to be created first by running `./gradlew test-app:assemble`.

Also the following environment variables need to be set:

```
APPLICATIONINSIGHTS_CONFIGURATION_FILE=/Users/peter.phillips/work/hmpps-spring-boot-sqs/test-app/applicationinsights.json
APPLICATIONINSIGHTS_CONNECTION_STRING=<connection string>
```

See the
[user guide](https://learn.microsoft.com/en-us/azure/azure-monitor/app/opentelemetry-enable?tabs=java#copy-the-connection-string-from-your-application-insights-resource)
to find out how to get the connection string from Application Insights.

### Viewing Application Insights

Received messages can be viewed
in [Application Insights](https://portal.azure.com/#browse/Microsoft.OperationalInsights%2Fworkspaces) by running:

```kusto
AppRequests
| where AppRoleName == "test-app"
```

Similarly for published messages:

```kusto
AppDependencies
| where AppRoleName == "test-app"
```

### Debugging Application Insights

Changing `applicationinsights.json` to include:

```
  "selfDiagnostics": {
    "destination": "console",
    "level": "DEBUG"
  },
```

will output debugging information to the running application logs. This means you don't then need to wait for the
messages to appear in Application Insights and you can see the results of your changes immediately.
