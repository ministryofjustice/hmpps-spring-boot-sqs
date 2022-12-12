# test-app

This is a copy of the project `hmpps-template-kotlin` and is being used to test the library `hmpps-spring-goot-sqs`.

This project should be kept up to date with the template project to keep it as close to real projects as possible.

## Running the test-app locally

Start localstack with command from the project root directory:

`docker-compose -f docker-compose-test.yml up`

The run the app in Intellij from class `App` with Spring active profiles `stdout,localstack`.

You should now be able to see the health page at `http://localhost:8080/health`.

