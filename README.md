# amazon-lex-importer
Import an Amazon Lex bot

It is implemented as one file with a reason - to be easier used by those who are not familiar with Java environment.

Dependencies:
```
aws-java-sdk-lexmodelbuilding
```
HOWTO add dependency: https://youtu.be/EAxiratt5_k?t=3m10s

HOWTO Connecting to the Amazon Lex model building service with a client from Java SDK: https://youtu.be/31vevfYPW_U

The import is performed from a json-file, where an Amazon Lex bot is exported by the util
https://github.com/awslabs/amazon-lex-bot-export

Create a user in AWS IAM service with the policy "AmazonLexFullAccess"
HOWTO create user: https://youtu.be/EAxiratt5_k?t=6m42s

Known issues/not-completed:
- Lambda functions mapping is created - in case if Lambda functions are in different account, region or have different name.
- FulfillmentActivityType is always set to "ReturnIntent" - HACK: Temporarily - as Lambda cannot be connected to intents doe to security issues: "Error: Lex is unable to access the Lambda function arn:aws:lambda:us-east-1:<USER_ID>:function:<Lambda-Name> in the context of intent arn:aws:lex:us-east-1:USER_ID:intent:<INTENT_NAME>:$LATEST.  Please check the resource-based policy on the function."
- ResponseCard has not been tested.
