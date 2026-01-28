#!/usr/bin/env bash
# Helper script to send a test message to the inbound queue via SQS.
# The type of this message means that it is re-transmitted onto the outbound topic which then is received again on the
# outbound queue

aws --endpoint-url=http://localhost:4566 sqs send-message \
  --queue-url http://sqs.eu-west-2.localhost.localstack.cloud:4566/000000000000/inbound-queue \
  --message-body '{"Message":"{\"id\":\"audit-id\",\"type\":\"OFFENDER_MOVEMENT-RECEPTION\",\"contents\":\"some event contents\"}","MessageId":"message-id1","MessageAttributes":{"eventType":{"Value":"OFFENDER_MOVEMENT-RECEPTION","Type":"String"}}}'
