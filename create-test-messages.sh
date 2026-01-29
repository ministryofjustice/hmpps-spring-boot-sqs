#!/usr/bin/env bash
# Helper script to send test messages to localstack.
# This requires localstack to be started and also one of the test apps to be running for the messages to be consumed.
# Please see RunningLocally.md for more information.

# By default it will send all the messages, otherwise it will send the specified message.
TYPE=${1:-all}

# Send a test message to the inbound-queue.
# This message contains all of the attribute information in the payload rather than the message body.
if [[ $TYPE == "all" || $TYPE == "sqs" ]]; then
  aws --endpoint-url=http://localhost:4566 sqs send-message \
    --queue-url http://sqs.eu-west-2.localhost.localstack.cloud:4566/000000000000/inbound-queue \
    --message-body '{"Message":"{\"id\":\"audit-id\",\"type\":\"OFFENDER_MOVEMENT-DISCHARGE\",\"contents\":\"some event contents\"}","MessageId":"message-id1","MessageAttributes":{"eventType":{"Value":"OFFENDER_MOVEMENT-DISCHARGE","Type":"String"}}}'
fi

# Send a test message to the inbound-queue that will then get published to a topic.
# This message contains all of the attribute information in the payload rather than the message body.
if [[ $TYPE == "all" || $TYPE == "sqs-emit" ]]; then
  aws --endpoint-url=http://localhost:4566 sqs send-message \
    --queue-url http://sqs.eu-west-2.localhost.localstack.cloud:4566/000000000000/inbound-queue \
    --message-body '{"Message":"{\"id\":\"audit-id\",\"type\":\"OFFENDER_MOVEMENT-RECEPTION\",\"contents\":\"some event contents\"}","MessageId":"message-id1","MessageAttributes":{"eventType":{"Value":"OFFENDER_MOVEMENT-RECEPTION","Type":"String"}}}'
fi

# Send a test message to the inbound-sqs-only-queue that will then get published to a queue.
# This message contains all of the attribute information in the header rather than the message body.
if [[ $TYPE == "all" || $TYPE == "sqs-header-emit" ]]; then
  aws --endpoint-url=http://localhost:4566 sqs send-message \
    --queue-url http://sqs.eu-west-2.localhost.localstack.cloud:4566/000000000000/inbound-sqs-only-queue \
    --message-body '{"type":"OFFENDER_MOVEMENT-RECEPTION","id":"1","contents":"reception_message_contents"}' \
    --message-attributes '{"eventType":{"StringValue":"OFFENDER_MOVEMENT-RECEPTION","DataType":"String"}}'
fi

# Send a test message to the inbound-topic.
if [[ $TYPE == "all" || $TYPE == "sns" ]]; then
  aws --endpoint-url=http://localhost:4566 sns publish \
    --topic-arn arn:aws:sns:eu-west-2:000000000000:inbound-topic \
    --message-attributes '{"eventType" : { "DataType":"String", "StringValue":"OFFENDER_MOVEMENT-DISCHARGE"}}' \
    --message '{"type":"OFFENDER_MOVEMENT-DISCHARGE","id":"2","contents":"discharge_message_contents"}'
fi

# Send a test message to the inbound-topic that will then published to a topic.
if [[ $TYPE == "all" || $TYPE == "sns-emit" ]]; then
  aws --endpoint-url=http://localhost:4566 sns publish \
    --topic-arn arn:aws:sns:eu-west-2:000000000000:inbound-topic \
    --message-attributes '{"eventType" : { "DataType":"String", "StringValue":"OFFENDER_MOVEMENT-RECEPTION"}}' \
    --message '{"type":"OFFENDER_MOVEMENT-RECEPTION","id":"1","contents":"reception_message_contents"}'
fi
