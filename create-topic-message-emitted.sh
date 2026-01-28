#!/usr/bin/env bash
# Helper script to send a test message to the inbound topic via SNS.
# The type of this message means that it is re-transmitted onto the outbound topic which then is received again on the
# outbound queue

aws --endpoint-url=http://localhost:4566 sns publish \
  --topic-arn arn:aws:sns:eu-west-2:000000000000:inbound-topic \
  --message-attributes '{"eventType" : { "DataType":"String", "StringValue":"OFFENDER_MOVEMENT-RECEPTION"}}' \
  --message '{"type":"OFFENDER_MOVEMENT-RECEPTION","id":"1","contents":"reception_message_contents"}'
