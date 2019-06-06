/*
 * Copyright 2019 Rohit Awate.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.rohitawate.everest.controllers.auth.oauth2;

import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXCheckBox;
import com.jfoenix.controls.JFXRippler;
import com.jfoenix.controls.JFXTextField;
import com.rohitawate.everest.auth.captors.CaptureMethod;
import com.rohitawate.everest.auth.oauth2.AuthorizationCodeFlowProvider;
import com.rohitawate.everest.auth.oauth2.OAuth2FlowProvider;
import com.rohitawate.everest.auth.oauth2.exceptions.AccessTokenDeniedException;
import com.rohitawate.everest.auth.oauth2.exceptions.AuthWindowClosedException;
import com.rohitawate.everest.auth.oauth2.exceptions.NoAuthorizationGrantException;
import com.rohitawate.everest.auth.oauth2.tokens.AuthCodeToken;
import com.rohitawate.everest.auth.oauth2.tokens.OAuth2Token;
import com.rohitawate.everest.controllers.DashboardController;
import com.rohitawate.everest.logging.Logger;
import com.rohitawate.everest.misc.EverestUtilities;
import com.rohitawate.everest.notifications.NotificationsManager;
import com.rohitawate.everest.state.auth.AuthorizationCodeState;
import com.rohitawate.everest.state.auth.OAuth2FlowState;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.ComboBox;
import javafx.scene.layout.VBox;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ResourceBundle;
import java.util.concurrent.ExecutorService;

public class AuthorizationCodeController extends OAuth2FlowController {
    @FXML
    private VBox authCodeBox, accessTokenBox;
    @FXML
    private JFXCheckBox enabled;
    @FXML
    private ComboBox<String> captureMethodBox;
    @FXML
    private JFXTextField authURLField, tokenURLField, redirectURLField,
            clientIDField, clientSecretField, scopeField, stateField,
            headerPrefixField, accessTokenField, refreshTokenField;
    @FXML
    private JFXButton refreshTokenButton;

    private JFXRippler rippler;

    private AuthorizationCodeState state;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        captureMethodBox.getItems().addAll(CaptureMethod.BROWSER, CaptureMethod.WEB_VIEW);
        captureMethodBox.setValue(CaptureMethod.BROWSER);
        refreshTokenButton.setOnAction(this::refreshToken);
        expiryLabel.setVisible(false);

        rippler = new JFXRippler(accessTokenBox);
        rippler.setPrefSize(authCodeBox.getPrefWidth(), authCodeBox.getPrefHeight());
        authCodeBox.getChildren().add(rippler);

        initExpiryCountdown();
    }

    @Override
    void refreshToken(ActionEvent actionEvent) {
        TokenFetcher tokenFetcher = new TokenFetcher();

        /*
            Opening a system browser window need not be done on the JavaFX Application Thread.
            Hence, this is performed on a separate thread.
         */
        if (captureMethodBox.getValue().equals(CaptureMethod.BROWSER)) {
            ExecutorService service = EverestUtilities.newDaemonSingleThreadExecutor();
            service.submit(tokenFetcher);
        } else {
            /*
                However, a WebView can only be opened on the JavaFX Application Thread hence it is
                NOT performed on some other thread.
             */
            try {
                state.accessToken = tokenFetcher.call();
                onRefreshSucceeded();
            } catch (Exception e) {
                onRefreshFailed(e);
            }
        }
    }

    @Override
    public OAuth2FlowState getState() {
        if (state == null) {
            state = new AuthorizationCodeState();
            return state;
        }

        state.captureMethod = captureMethodBox.getValue();
        state.authURL = authURLField.getText();
        state.accessTokenURL = tokenURLField.getText();
        state.redirectURL = redirectURLField.getText();
        state.clientID = clientIDField.getText();
        state.clientSecret = clientSecretField.getText();
        state.scope = scopeField.getText();
        state.state = stateField.getText();
        state.headerPrefix = headerPrefixField.getText();
        state.enabled = enabled.isSelected();

        // Setting these values again since they can be modified from the UI
        AuthCodeToken token = (AuthCodeToken) state.accessToken;
        token.setAccessToken(accessTokenField.getText());
        token.setRefreshToken(refreshTokenField.getText());

        return state;
    }

    @Override
    public void setState(OAuth2FlowState authCodeState) {
        this.state = (AuthorizationCodeState) authCodeState;

        if (authCodeState != null) {
            captureMethodBox.setValue(state.captureMethod);

            authURLField.setText(state.authURL);
            tokenURLField.setText(state.accessTokenURL);
            redirectURLField.setText(state.redirectURL);

            clientIDField.setText(state.clientID);
            clientSecretField.setText(state.clientSecret);

            scopeField.setText(state.scope);
            stateField.setText(state.state);
            headerPrefixField.setText(state.headerPrefix);
            enabled.setSelected(state.enabled);

            if (state.accessToken != null) {
                onRefreshSucceeded();
            }
        }
    }

    @Override
    public void reset() {
        authURLField.clear();
        tokenURLField.clear();
        redirectURLField.clear();
        clientIDField.clear();
        clientSecretField.clear();
        scopeField.clear();
        stateField.clear();
        headerPrefixField.clear();
        accessTokenField.clear();
        refreshTokenField.clear();
        expiryLabel.setVisible(false);
        enabled.setSelected(false);
        state = null;
    }

    @Override
    public OAuth2FlowProvider getAuthProvider() {
        /*
            This method is always called on the JavaFX application thread, which is also required for
            creating and using the WebView. Hence, refreshToken() is called here itself if the accessToken is absent,
            so that when RequestManager invokes AuthCodeProvider's getAuthHeader() from a different thread,
            the accessToken is already present and hence the WebView wouldn't need to be opened.
         */
        String token = accessTokenField.getText();
        if (token != null && token.isEmpty() && enabled.isSelected() &&
                captureMethodBox.getValue().equals(CaptureMethod.WEB_VIEW)) {
            refreshToken(null);
        }

        return new AuthorizationCodeFlowProvider(this);
    }

    @Override
    void onRefreshSucceeded() {
        accessTokenField.clear();
        refreshTokenField.clear();

        accessTokenField.setText(state.accessToken.getAccessToken());

        AuthCodeToken token = (AuthCodeToken) state.accessToken;
        if (token.getRefreshToken() != null) {
            refreshTokenField.setText(token.getRefreshToken());
        }

        setExpiryLabel();

        rippler.createManualRipple().run();
    }

    @Override
    void onRefreshFailed(Throwable exception) {
        String errorMessage;
        if (exception.getClass().equals(AuthWindowClosedException.class)) {
            // DashboardController already shows an error for this
            return;
        } else if (exception.getClass().equals(NoAuthorizationGrantException.class)) {
            errorMessage = "Grant denied by authorization endpoint.";
        } else if (exception.getClass().equals(AccessTokenDeniedException.class)) {
            errorMessage = "Access token denied by token endpoint.";
        } else if (exception.getClass().equals(MalformedURLException.class)) {
            errorMessage = "Invalid URL(s).";
        } else {
            errorMessage = "Could not refresh OAuth 2.0 Authorization Code tokens.";
        }

        NotificationsManager.push(DashboardController.CHANNEL_ID, errorMessage, 10000);
        Logger.warning(errorMessage, (Exception) exception);
    }

    @Override
    public void setAccessToken(OAuth2Token accessToken) {
        state.accessToken = accessToken;
        Platform.runLater(() -> {
            onRefreshSucceeded();
            accessTokenField.requestLayout();
            refreshTokenField.requestLayout();
        });
    }

    private class TokenFetcher extends Task<OAuth2Token> {
        @Override
        protected OAuth2Token call() throws Exception {
            AuthorizationCodeFlowProvider provider = new AuthorizationCodeFlowProvider(AuthorizationCodeController.this);
            return provider.getAccessToken();
        }

        @Override
        protected void succeeded() {
            state.accessToken = getValue();
            onRefreshSucceeded();
        }

        @Override
        protected void failed() {
            Throwable exception = getException();
            onRefreshFailed(exception);
        }
    }
}
