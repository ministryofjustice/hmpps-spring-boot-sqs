#!/usr/bin/env bash
# Helper script to send a test message to the inbound queue via SQS.

aws --endpoint-url=http://localhost:4566 sqs send-message \
  --queue-url http://sqs.eu-west-2.localhost.localstack.cloud:4566/000000000000/inbound-queue \
  --message-body '{"Message":"{\"id\":\"audit-id\",\"type\":\"OFFENDER_MOVEMENT-DISCHARGE\",\"contents\":\"some event contents\"}","MessageId":"message-id1","MessageAttributes":{"eventType":{"Value":"OFFENDER_MOVEMENT-DISCHARGE","Type":"String"}}}'
