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

import com.google.common.util.concurrent.ListenableFuture;
import com.linecorp.platform.channel.sample.pojos.*;
import org.apache.commons.codec.binary.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.UnsupportedEncodingException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

public class BusinessConnect {

    private final static String API_URL_EVENTS = "https://api.line.me/v1/events";
    private final static String API_URL_PROFILES = "https://api.line.me/v1/profiles";

    public BusinessConnect() {
    }

    public boolean validateBCRequest(String httpRequestBody, String channelSecret, String channelSignature) {
        if (httpRequestBody == null || channelSecret == null || channelSignature == null) {
            return false;
        }

        String signature;
        SecretKeySpec key = new SecretKeySpec(channelSecret.getBytes(), "HmacSHA256");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            byte[] source = httpRequestBody.getBytes("UTF-8");
            signature = Base64.encodeBase64String(mac.doFinal(source));
        } catch (NoSuchAlgorithmException | InvalidKeyException | UnsupportedEncodingException e) {
            e.printStackTrace();
            return false;
        }
        return channelSignature.equals(signature);
    }

    public void sendTextMessage(List<String> toUsers, String message, ApiHttpClient apiHttpClient) {
        OutgoingMessage outgoingMessage = new OutgoingMessage();
        outgoingMessage.setTo(toUsers);
        outgoingMessage.setToChannel(Constants.ToChannel.MESSAGE);
        outgoingMessage.setEventType(Constants.EventType.OUTGOING_MESSAGE);
        MessageContent messageContent = new MessageContent();
        messageContent.setContentType(Constants.ContentType.TEXT);
        messageContent.setToType(Constants.ToType.USER);
        messageContent.setText(message);
        outgoingMessage.setContent(messageContent);

        ListenableFuture<ApiResponse> apiListenableFuture = apiHttpClient.sendMessage(API_URL_EVENTS, outgoingMessage);
        if (apiListenableFuture != null) {
            try {
                ApiResponse apiResponse = apiListenableFuture.get();
            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
            }
        }
    }

    public Profile getProfile(String mid, ApiHttpClient apiHttpClient) {
        ProfileList profileList = null;
        String url = String.format("%s?mids=%s", API_URL_PROFILES, mid);
        ListenableFuture<ProfileList> apiListenableFuture = apiHttpClient.getProfileList(url);
        if (apiListenableFuture != null) {
            try {
                profileList = apiListenableFuture.get();
            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
            }
        }
        if (profileList != null && profileList.getContacts().size() > 0) {
            return profileList.getContacts().get(0);
        }
        return null;
    }

    public void addFriend(String mid, String displayName, Connection connection) {
        try (Statement stmt = connection.createStatement();
             PreparedStatement pstmt = connection.prepareStatement("INSERT INTO friends VALUES (?, ?, now())");
            ) {
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS friends (mid VARCHAR(100) CONSTRAINT friends_pk PRIMARY KEY, " +
                    "display VARCHAR(300), occasion TIMESTAMP)");

            pstmt.setString(1, mid);
            pstmt.setString(2, displayName);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public List<String> getFriends(String currentMid, Connection connection) {
        List<String> output = new ArrayList<>();
        try (PreparedStatement pstmt = connection.prepareStatement("SELECT mid FROM friends WHERE mid <> ?")) {
            pstmt.setString(1, currentMid);
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                output.add(rs.getString("mid"));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        return output;
    }
}
