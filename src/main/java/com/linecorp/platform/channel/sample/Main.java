/*
 * Copyright 2015 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.platform.channel.sample;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.fasterxml.jackson.module.jaxb.JaxbAnnotationIntrospector;
import com.heroku.sdk.jdbc.DatabaseUrl;
import com.linecorp.platform.channel.sample.pojos.Event;
import com.linecorp.platform.channel.sample.pojos.EventList;
import com.linecorp.platform.channel.sample.pojos.Profile;
import spark.ModelAndView;
import spark.template.freemarker.FreeMarkerEngine;

import java.io.IOException;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static spark.Spark.get;
import static spark.Spark.post;
import static spark.SparkBase.port;
import static spark.SparkBase.staticFileLocation;

public class Main {

    public static void main(String[] args) {

        BusinessConnect bc = new BusinessConnect();

        /**
         * Prepare the required channel secret and access token
         */
        String channelSecret = System.getenv("CHANNEL_SECRET");
        String channelAccessToken = System.getenv("CHANNEL_ACCESS_TOKEN");
        if (channelSecret == null || channelSecret.isEmpty() || channelAccessToken == null ||
                channelAccessToken.isEmpty()) {
            System.err.println("Error! Environment variable CHANNEL_SECRET and CHANNEL_ACCESS_TOKEN not defined.");
            return;
        }

        port(Integer.valueOf(System.getenv("PORT")));
        staticFileLocation("/public");

        /**
         * Define the callback url path
         */
        post("/events", (request, response) -> {
            String requestBody = request.body();

            /**
             * Verify whether the channel signature is valid or not
             */
            String channelSignature = request.headers("X-LINE-CHANNELSIGNATURE");
            if (channelSignature == null || channelSignature.isEmpty()) {
                response.status(400);
                return "Please provide valid channel signature and try again.";
            }
            if (!bc.validateBCRequest(requestBody, channelSecret, channelSignature)) {
                response.status(401);
                return "Invalid channel signature.";
            }

            /**
             * Parse the http request body
             */
            ObjectMapper objectMapper = new ObjectMapper();
            objectMapper.configure(MapperFeature.USE_WRAPPER_NAME_AS_PROPERTY_NAME, true);
            objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            objectMapper.configure(SerializationFeature.WRITE_NULL_MAP_VALUES, false);
            objectMapper.setAnnotationIntrospector(new JaxbAnnotationIntrospector(TypeFactory.defaultInstance()));
            objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);

            EventList events;
            try {
                events = objectMapper.readValue(requestBody, EventList.class);
            } catch (IOException e) {
                response.status(400);
                return "Invalid request body.";
            }

            ApiHttpClient apiHttpClient = new ApiHttpClient(channelAccessToken);

            /**
             * Process the incoming messages/operations one by one
             */
            List<String> toUsers;
            for (Event event : events.getResult()) {
                switch (event.getEventType()) {
                    case Constants.EventType.MESSAGE:
                        toUsers = new ArrayList<>();
                        toUsers.add(event.getContent().getFrom());

                        // @TODO: We strongly suggest you should modify this to process the incoming message/operation async
                        bc.sendTextMessage(toUsers, "You said: " + event.getContent().getText(), apiHttpClient);
                        break;
                    case Constants.EventType.OPERATION:
                        if (event.getContent().getOpType() == Constants.OperationType.ADDED_AS_FRIEND) {
                            String newFriend = event.getContent().getParams().get(0);
                            Profile profile = bc.getProfile(newFriend, apiHttpClient);
                            String displayName = profile == null ? "Unknown" : profile.getDisplayName();
                            toUsers = new ArrayList<>();
                            toUsers.add(newFriend);
                            bc.sendTextMessage(toUsers, displayName + ", welcome to be my friend!", apiHttpClient);
                            Connection connection = null;
                            connection = DatabaseUrl.extract().getConnection();
                            toUsers = bc.getFriends(newFriend, connection);
                            if (toUsers.size() > 0) {
                                bc.sendTextMessage(toUsers, displayName +
                                        " just join us, let's welcome him/her!", apiHttpClient);
                            }
                            bc.addFriend(newFriend, displayName, connection);
                            if (connection != null) {
                                connection.close();
                            }
                        }
                        break;
                    default:
                        // Unknown type?
                }
            }
            return "Events received successfully.";
        });

        get("/", (request, response) -> {
            Map<String, Object> attributes = new HashMap<>();
            attributes.put("message", "Hello World!");
            return new ModelAndView(attributes, "index.ftl");
        }, new FreeMarkerEngine());
    }
}
