/*
 * Copyright 2014 the original author or authors.
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

package com.bearchoke.platform.domain.user.security;

import com.bearchoke.platform.api.user.UserDetailsExtended;
import com.bearchoke.platform.api.user.command.AuthenticateUserCommand;
import com.bearchoke.platform.api.user.UserAccount;
import lombok.extern.log4j.Log4j2;
import org.axonframework.commandhandling.CommandBus;
import org.axonframework.commandhandling.GenericCommandMessage;
import org.axonframework.commandhandling.StructuralCommandValidationFailedException;
import org.axonframework.commandhandling.callbacks.FutureCallback;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.concurrent.ExecutionException;

/**
 * A custom spring security authentication provider that only supports {@link org.springframework.security.authentication.UsernamePasswordAuthenticationToken}
 * authentications. This provider uses Axon's command bus to dispatch an authentication command. The main reason for
 * creating a custom authentication provider is that Spring's UserDetailsService model doesn't fit our authentication
 * model as the UserAccount doesn't hold the password (UserDetailsService expects the UserDetails object to hold the
 * password, which is then compared with the password provided by the {@link org.springframework.security.authentication.UsernamePasswordAuthenticationToken}.
 *
 * @author Bjorn Harvold
 */
@Log4j2
public class UserAuthenticationProvider implements AuthenticationProvider {

    private final CommandBus commandBus;

    public UserAuthenticationProvider(CommandBus commandBus) {
        this.commandBus = commandBus;
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        if (!supports(authentication.getClass())) {
            return null;
        }

        if (log.isDebugEnabled()) {
            log.debug("Authenticating: " + authentication);
        }

        UsernamePasswordAuthenticationToken token = (UsernamePasswordAuthenticationToken) authentication;
        String username = token.getName();
        String password = String.valueOf(token.getCredentials());
        FutureCallback<UserDetailsExtended> accountCallback = new FutureCallback<>();
        AuthenticateUserCommand command = new AuthenticateUserCommand(username, password);

        try {
            commandBus.dispatch(new GenericCommandMessage<>(command), accountCallback);
            // the bean validating interceptor is defined as a dispatch interceptor, meaning it is executed before
            // the command is dispatched.
        } catch (StructuralCommandValidationFailedException e) {
            log.error(e.getMessage(), e);
            return null;
        }

        UserDetailsExtended account;

        try {
            account = accountCallback.get();

            if (account == null) {
                String error = String.format("Could not locate user record for username: %s", username);
                if (log.isDebugEnabled()) {
                    log.debug(error);
                }
                throw new UsernameNotFoundException(error);
            }

        } catch (InterruptedException | ExecutionException e) {
            log.error(e.getMessage(), e);
            throw new AuthenticationServiceException("Credentials could not be verified", e);
        }

        UsernamePasswordAuthenticationToken result =
                new UsernamePasswordAuthenticationToken(account, authentication.getCredentials(), account.getAuthorities());
        result.setDetails(authentication.getDetails());

        if (log.isDebugEnabled()) {
            log.debug("Authentication successful: " + result);
        }

        return result;
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
