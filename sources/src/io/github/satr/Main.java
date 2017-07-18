package io.github.satr;

import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.lexmodelbuilding.AmazonLexModelBuilding;
import com.amazonaws.services.lexmodelbuilding.AmazonLexModelBuildingClientBuilder;
import com.amazonaws.services.lexmodelbuilding.model.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.util.*;
import java.util.concurrent.Callable;

import static org.apache.http.util.TextUtils.isEmpty;

public class Main {

    private static final String LATEST_VERSION = "$LATEST";
    private static final String BUILD_IN_AMAZON_SLOT_PREFIX = "AMAZON.";

    //Dependencies: aws-java-sdk-lexmodelbuilding
    //HOWTO add dependency: https://youtu.be/EAxiratt5_k?t=3m10s

    //It is implemented as one file with a reason - to be easier used by those who are not familiar with Java environment
    public static void main(String[] args) {

        //The path to a json-file, exported by the util: https://github.com/awslabs/amazon-lex-bot-export
        String jsonFullFilePath = "/path/*************.json";

        //Create a user in AWS IAM service with the policy "AmazonLexFullAccess"
        //HOWTO create user: https://youtu.be/EAxiratt5_k?t=6m42s
        String accessKey = "*****************";//IAM User's Access key ID
        String secretKey = "*****************************";//IAM User's Access Secret key

        //in case if Lambda functions are in different account, region or have different name
        HashMap<String, String> lambdaFunctionsMap = new HashMap<>();
        lambdaFunctionsMap.put("arn:aws:lambda:us-east-1:<YOUR_ACCOUNT_ID>:function:LambdaFunctionName", "arn:aws:lambda:us-east-1:<YOUR_ACCOUNT_ID>:function:LambdaFunctionName");

        AmazonLexModelBuilding client = null;
        try {
            BasicAWSCredentials credentials = new BasicAWSCredentials(accessKey, secretKey);
            client = AmazonLexModelBuildingClientBuilder.standard()
                    .withCredentials(new AWSStaticCredentialsProvider(credentials))
                    .withRegion(Regions.US_EAST_1).build();

            File exportedFile = new File(jsonFullFilePath);
            if(!exportedFile.exists()){
                System.out.println(String.format("The exported file does not exist."));
                return;
            }

            Map<String, Object> botMap = new ObjectMapper().readValue(exportedFile,
                    new TypeReference<Map<String, Object>>() {});

            Map<String, Object> dependencies = (Map<String, Object>) botMap.get("dependencies");

            ArrayList<Map<String, Object>> slotTypeMaps = (ArrayList<Map<String, Object>>) dependencies.get("slotTypes");
            importSlotTypes(client, slotTypeMaps);

            ArrayList<Map<String, Object>> intentMaps = (ArrayList<Map<String, Object>>) dependencies.get("intents");
            importIntents(client, lambdaFunctionsMap, intentMaps);

            final boolean buildAfterSaving = false;
            importBot(client, botMap, intentMaps, buildAfterSaving);

            botList(client);
            intentList(client);
            slotTypeList(client);

        }catch (Exception e){
            e.printStackTrace();
        } finally {
            if(client != null)
                client.shutdown();
        }
    }

    private static void importBot(AmazonLexModelBuilding client, Map<String, Object> map,
                                  ArrayList<Map<String, Object>> intentMaps, boolean buildAfterSaving) {
        ArrayList<Message> clarificationPromptMessages = new ArrayList<>();
        Map<String, Object> clarificationPromptMap = (Map<String, Object>) map.get("clarificationPrompt");
        for(Map<String, Object> promptMessageMap: (ArrayList<Map<String, Object>>)clarificationPromptMap.get("messages")) {
            clarificationPromptMessages.add(new Message().withContentType((String) promptMessageMap.get("contentType"))
                    .withContent((String) promptMessageMap.get("content")));
        }
        Prompt clarificationPrompt = new Prompt().withMaxAttempts((Integer) clarificationPromptMap.get("maxAttempts"))
                .withMessages(clarificationPromptMessages)
                .withResponseCard((String) clarificationPromptMap.get("responseCard"));

        Map<String, Object> abortStatementMap = (Map<String, Object>) map.get("abortStatement");
        ArrayList<Message> abortStatementMessages = new ArrayList<>();
        for(Map<String, Object> abortStatementMessageMap: (ArrayList<Map<String, Object>>)abortStatementMap.get("messages")) {
            abortStatementMessages.add(new Message().withContentType((String) abortStatementMessageMap.get("contentType"))
                    .withContent((String) abortStatementMessageMap.get("content")));
        }
        Statement abortStatement = new Statement()
                .withMessages(abortStatementMessages)
                .withResponseCard((String) clarificationPromptMap.get("responseCard"));

        String botName = (String) map.get("name");
        Integer idleSessionTTLInSeconds = (Integer) map.get("idleSessionTTLInSeconds");
        String description = (String) map.get("description");
        String voiceId = (String) map.get("voiceId");
        String locale = (String) map.get("locale");
        boolean childDirected = (boolean) map.get("childDirected");

        ArrayList<String> intentNames = new ArrayList<>();
        for(Map<String, Object> intentMap: intentMaps)
            intentNames.add((String) intentMap.get("name"));

        tryCreateOrUpdateBot(client, botName, buildAfterSaving, intentNames, idleSessionTTLInSeconds,
                description, voiceId, clarificationPrompt, locale, abortStatement, childDirected);
    }

    private static void importIntents(AmazonLexModelBuilding client, HashMap<String, String> lambdaFunctionsMap, ArrayList<Map<String, Object>> intentMaps) {
        for(Map<String, Object> intentMap: intentMaps) {
            ArrayList<Slot> slots = getSlots(intentMap);
            ArrayList<String> sampleUtterances = (ArrayList<String>)intentMap.get("sampleUtterances");

            Map<String, Object> fulfillmentActivityMap = (Map<String, Object>)intentMap.get("fulfillmentActivity");
            String fulfillmentActivityType = (String) fulfillmentActivityMap.get("type");

            fulfillmentActivityType = "ReturnIntent";//HACK!: Temporarily - as Lambda cannot be connected to intents doe to security issues

            FulfillmentActivity fulfillmentActivity = new FulfillmentActivity().withType(fulfillmentActivityType);

            Map<String, Object> codeHookMap = (Map<String, Object>) fulfillmentActivityMap.get("codeHook");
            CodeHook dialogCodeHook = null;
            if(fulfillmentActivity.getType().equals("CodeHook") && codeHookMap != null)
            {
                String lambdaFunctionUri = (String) codeHookMap.get("uri");
                if(lambdaFunctionsMap.containsKey(lambdaFunctionUri) && !isEmpty(lambdaFunctionsMap.get(lambdaFunctionUri)))
                    lambdaFunctionUri = lambdaFunctionsMap.get(lambdaFunctionUri);
                dialogCodeHook = new CodeHook().withUri(lambdaFunctionUri)
                        .withMessageVersion((String) codeHookMap.get("messageVersion"));
            }
            String intentName = (String) intentMap.get("name");
            String description = (String) intentMap.get("description");
            tryCreateOrUpdateIntent(client, intentName, description, slots, sampleUtterances, fulfillmentActivity, dialogCodeHook);
        }
    }

    private static ArrayList<Slot> getSlots(Map<String, Object> intent) {
        ArrayList<Slot> slots = new ArrayList<>();
        for(Map<String, Object> slotMap: (ArrayList<Map<String, Object>>)intent.get("slots")) {
            final Map<String, Object> valueElicitationPrompt = (Map<String, Object>) slotMap.get("valueElicitationPrompt");
            final ArrayList<Message> messages = new ArrayList<>();
            for(Map<String, Object> messageEntry: (ArrayList<Map<String, Object>>) valueElicitationPrompt.get("messages")) {
                messages.add(new Message().withContentType((String) messageEntry.get("contentType"))
                        .withContent((String) messageEntry.get("content")));
            }

            Integer maxAttempts = (Integer) valueElicitationPrompt.get("maxAttempts");
            String responseCard = (String) valueElicitationPrompt.get("responseCard");//TODO - not supported yet
            Prompt prompt = new Prompt().withMaxAttempts(maxAttempts)
                    .withMessages(messages)
                    .withResponseCard(responseCard);
            Integer priority = (Integer) slotMap.get("priority");
            final Slot slot = new Slot().withName((String) slotMap.get("name"))
                    .withDescription((String) slotMap.get("description"))
                    .withSlotType((String) slotMap.get("slotType"))//"AMAZON.NUMBER")
                    .withValueElicitationPrompt(prompt)
                    .withPriority(priority);

            slot.setSlotConstraint((String) slotMap.get("slotConstraint"));

            if(!slot.getSlotType().startsWith(BUILD_IN_AMAZON_SLOT_PREFIX))//do not specify a version for built in slot-types
                slot.setSlotTypeVersion(LATEST_VERSION);

            slots.add(slot);
        }
        return slots;
    }

    private static void importSlotTypes(AmazonLexModelBuilding client, ArrayList<Map<String, Object>> slotTypeMaps) {
        for(Map<String, Object> slotTypeMap: slotTypeMaps){
            ArrayList<EnumerationValue> values = new ArrayList<>();
            ArrayList<Map<String, Object>> enumerationValues = (ArrayList<Map<String, Object>>) slotTypeMap.get("enumerationValues");
            for(Map<String, Object> valueEntry: enumerationValues)
                values.add(new EnumerationValue().withValue((String) valueEntry.get("value")));
            String name = (String) slotTypeMap.get("name");
            String description = (String) slotTypeMap.get("description");

            tryCreateOrUpdateSlotType(client, name, values, description);
        }
    }

    private static void botList(AmazonLexModelBuilding client) {
        System.out.println("-- Bots --");
        for(BotMetadata bot: client.getBots(new GetBotsRequest()).getBots())
            System.out.println(String.format("Bot: %s (%s), status: %s, updated: %s",
                    bot.getName(), bot.getVersion(), bot.getStatus(), bot.getLastUpdatedDate()));
    }

    private static void intentList(AmazonLexModelBuilding client) {
        System.out.println("-- Intents --");
        for(IntentMetadata bot: client.getIntents(new GetIntentsRequest()).getIntents())
            System.out.println(String.format("Intent: %s (%s), updated: %s",
                    bot.getName(), bot.getVersion(), bot.getLastUpdatedDate()));
    }

    private static void slotTypeList(AmazonLexModelBuilding client) {
        System.out.println("-- Slot Types --");
        for(SlotTypeMetadata bot: client.getSlotTypes(new GetSlotTypesRequest()).getSlotTypes())
            System.out.println(String.format("Slot type: %s (%s), updated: %s",
                    bot.getName(), bot.getVersion(), bot.getLastUpdatedDate()));
    }

    private static void delete(AmazonLexModelBuilding client, String botName, String aliasName, String intentName, String slotTypeName) {
        System.out.println("-- Delete --");
        deleteBotAlias(client, botName, aliasName);
        deleteBot(client, botName);
        deleteIntent(client, intentName);
        deleteSlotType(client, slotTypeName);
    }

    private static void deleteSlotType(AmazonLexModelBuilding client, String slotTypeName) {
        if(!isSlotTypeExist(client, slotTypeName))
            return;

        DeleteSlotTypeRequest request = new DeleteSlotTypeRequest().withName(slotTypeName);
        performRequest(slotTypeName, () -> {
            client.deleteSlotType(request);
            System.out.println(String.format("Slot type \"%s\" successfully deleted.", slotTypeName));
            return true;
        });
    }

    private static boolean isSlotTypeExist(AmazonLexModelBuilding client, String slotTypeName) {
        for(SlotTypeMetadata metadata: client.getSlotTypes(new GetSlotTypesRequest()).getSlotTypes()) {
            if (metadata.getName().equals(slotTypeName))
                return true;
        }
        return false;
    }

    private static void deleteIntent(AmazonLexModelBuilding client, String intentName) {
        if(!isIntentExist(client, intentName))
            return;

        DeleteIntentRequest request = new DeleteIntentRequest().withName(intentName);
        performRequest(intentName, () -> {
            client.deleteIntent(request);
            System.out.println(String.format("Intent \"%s\" successfully deleted.", intentName));
            return true;
        });
        return;
    }

    private static boolean isIntentExist(AmazonLexModelBuilding client, String intentName) {
        for(IntentMetadata metadata: client.getIntents(new GetIntentsRequest()).getIntents()) {
            if (metadata.getName().equals(intentName))
                return true;
        }
        return false;
    }

    private static void deleteBotAlias(AmazonLexModelBuilding client, String botName, String aliasName) {
        if(!isBotAliaseExist(client, botName, aliasName))
            return;

        DeleteBotAliasRequest request = new DeleteBotAliasRequest().withBotName(botName).withName(aliasName);
        performRequest(aliasName, () -> {
            client.deleteBotAlias(request);
            System.out.println(String.format("Bot alias \"%s\" for the bot %s successfully deleted.", aliasName, botName));
            return true;
        });
    }

    private static void deleteBot(AmazonLexModelBuilding client, String botName) {
        if (!isBotExist(client, botName))
            return;

        DeleteBotRequest request = new DeleteBotRequest().withName(botName);
        performRequest(botName, () -> {
            client.deleteBot(request);
            System.out.println(String.format("Bot \"%s\" successfully deleted.", botName));
            return true;
        });
    }

    private static boolean isBotExist(AmazonLexModelBuilding client, String botName) {
        for(BotMetadata metadata: client.getBots(new GetBotsRequest()).getBots()) {
            if(metadata.getName().equals(botName))
                return true;
        }
        return false;
    }

    private static boolean tryCreateBotAlias(AmazonLexModelBuilding client, String botName, String aliasName) {
        String checksum = getBotAliasChecksum(client, botName, aliasName);

        PutBotAliasRequest request = new PutBotAliasRequest().withBotName(botName)
                .withBotVersion(LATEST_VERSION)//aliases can be created for a particular version of a bot!
                .withName(aliasName)
                .withChecksum(checksum)
                .withDescription("Some description");

        return performRequest(aliasName, () -> {
            PutBotAliasResult result = client.putBotAlias(request);
            System.out.println(String.format("Bot alias \"%s\" for the bot \"%s\" successfully %s. Checksum: %s",
                    aliasName, botName, checksum == null ? "created" : "updated", result.getChecksum()));
            return true;
        });
    }

    private static String getBotAliasChecksum(AmazonLexModelBuilding client, String botName, String aliasName) {
        if(isBotAliaseExist(client, botName, aliasName)){
            GetBotAliasResult botAlias = client.getBotAlias(new GetBotAliasRequest().withBotName(botName).withName(aliasName));
            if(botAlias != null)
                return botAlias.getChecksum();
        }
        return null;
    }

    private static boolean isBotAliaseExist(AmazonLexModelBuilding client, String botName, String aliasName) {
        GetBotAliasesResult botAliases = client.getBotAliases(new GetBotAliasesRequest().withBotName(botName));
        for(BotAliasMetadata metadata: botAliases.getBotAliases()){
            if(metadata.getName().equals(aliasName))
                return true;
        }
        return false;
    }

    private static boolean tryCreateOrUpdateSlotType(AmazonLexModelBuilding client, String slotTypeName, ArrayList<EnumerationValue> values, String description) {
        String checksum = getSlotTypeChecksum(client, slotTypeName);


        final PutSlotTypeRequest request = new PutSlotTypeRequest()
                .withName(slotTypeName)
                .withChecksum(checksum)
                .withDescription(description)
                .withEnumerationValues(values);

        return performRequest(slotTypeName, () -> {
            final PutSlotTypeResult result = client.putSlotType(request);
            System.out.println(String.format("Slot type \"%s\" successfully %s. Checksum: %s", slotTypeName,
                    checksum == null ? "created" : "updated", result.getChecksum()));
            return true;
        });
    }

    private static String getSlotTypeChecksum(AmazonLexModelBuilding client, String slotTypeName) {
        if(isSlotTypeExist(client, slotTypeName)) { //is a slot type does not exist - getSlotType returns an error "The specified resource does not exist"
            GetSlotTypeResult existingSlotType = client.getSlotType(new GetSlotTypeRequest().withName(slotTypeName).withVersion(LATEST_VERSION));
            if (existingSlotType != null)
                return existingSlotType.getChecksum();
        }
        return null;//for a new slot-type it should be empty (null)
    }

    private static boolean tryCreateOrUpdateIntent(AmazonLexModelBuilding client, String intentName,
                                                   String description, List<Slot> slots, ArrayList<String> utterances, FulfillmentActivity fulfillmentActivity, CodeHook dialogCodeHook) {
        String checksum = getIntentChecksum(client, intentName);

        //String invalidIntentName = "AMAZON.HelpIntent";//both intents exist - "AMAZON." will be removed
        //String invalidIntentName = "HelpIntent";//

        final PutIntentRequest request = new PutIntentRequest().withName(intentName)
                .withChecksum(checksum)
                .withDescription(description)
                .withSlots(slots)
                .withSampleUtterances(utterances)
                .withFulfillmentActivity(fulfillmentActivity)
                .withDialogCodeHook(dialogCodeHook);
        return performRequest(intentName, () -> {
            final PutIntentResult result = client.putIntent(request);
            System.out.println(String.format("Intent \"%s\" successfully %s. Checksum: %s", intentName,
                    checksum == null ? "created" : "updated", result.getChecksum()));
            return true;
        });
    }

    private static String getIntentChecksum(AmazonLexModelBuilding client, String intentName) {
        if(isIntentExist(client, intentName)) { //is an intent does not exist - getIntent returns an error "The specified resource does not exist"
            GetIntentResult existingIntent = client.getIntent(new GetIntentRequest().withName(intentName).withVersion(LATEST_VERSION));
            if (existingIntent != null)
                return existingIntent.getChecksum();
        }
        return null;//for a new intent it should be empty (null)
    }

    private static boolean tryCreateOrUpdateBot(AmazonLexModelBuilding client, String botName, boolean buildAfterSaving,
                                                List<String> intentNames, int sessionTTLInSeconds, String description,
                                                String voiceId, Prompt clarificationPrompt, String locale,
                                                Statement abortStatement, boolean childDirected) {
        String checksum = getBotChecksum(client, botName);

        final PutBotRequest request = new PutBotRequest();
        request.withName(botName).withDescription(description)
                .withChildDirected(childDirected)
                .withProcessBehavior(buildAfterSaving ? ProcessBehavior.BUILD : ProcessBehavior.SAVE)
                .withVoiceId(voiceId) //http://docs.aws.amazon.com/polly/latest/dg/voicelist.html
                .withChecksum(checksum)
                .withLocale(locale)
                .withAbortStatement(abortStatement)
                .withIdleSessionTTLInSeconds(sessionTTLInSeconds)
                .withClarificationPrompt(clarificationPrompt);

        final ArrayList<Intent> intents = new ArrayList<>();
        for(String intentName: intentNames)
            intents.add(new Intent().withIntentName(intentName).withIntentVersion(LATEST_VERSION));

        request.setIntents(intents);

        return performRequest(botName, () -> {
            final PutBotResult result = client.putBot(request);
            System.out.println(String.format("Bot \"%s\" successfully %s. Checksum: %s", botName,
                    checksum == null ? "created" : "updated", result.getChecksum()));
            return true;
        });
    }

    private static String getBotChecksum(AmazonLexModelBuilding client, String botName) {
        if(isBotExist(client, botName)) { //is a bot does not exist - getBot returns an error "The specified resource does not exist"
            GetBotResult existingBot = client.getBot(new GetBotRequest().withName(botName).withVersionOrAlias(LATEST_VERSION));
            if (existingBot != null)
                return existingBot.getChecksum();
        }
        return null;//for a new bot it should be empty (null)
    }

    private static boolean performRequest(String entityName, Callable<Boolean> callableRequest) {
        try {
            return callableRequest.call();
        } catch (BadRequestException | ConflictException | InternalFailureException | LimitExceededException | PreconditionFailedException e) {
            System.out.println(String.format("Request for \"%s\" failed. Error: %s", entityName, e.getMessage()));
        } catch (Exception e) {
            System.out.println(e.fillInStackTrace());
        }
        return false;
    }
}
