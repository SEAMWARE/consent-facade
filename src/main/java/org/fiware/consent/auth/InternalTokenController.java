/*
 * Copyright 2026 Seamless Middleware Technologies S.L and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.fiware.consent.auth;

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Controller;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fiware.consent.internal.api.TokensApi;
import org.fiware.consent.internal.model.TokenRequestVO;
import org.fiware.consent.internal.model.TokenResponseVO;

/**
 * Hands out OID4VP access tokens for the configured audiences, so that components which do not
 * implement OID4VP - notably the Go consent-plugin - can authenticate as this participant.
 *
 * <p><strong>Internal endpoint.</strong> Declared in {@code api/consent-facade-internal.yaml}, not
 * in {@code api/consent-facade.yaml} (the contract towards the consent-manager), and it must never
 * be published: the facade ingress allow-lists only {@code /participants} and {@code /catalog}, and
 * access is further restricted to the APISIX pods by NetworkPolicy. Anything that reaches it can
 * make the facade present this participant's credential to a configured audience. The path comes
 * from the generated {@link TokensApi}.
 *
 * <p>Only active when OID4VP is enabled. See ADR-0003 in {@code doc/adr/} for why this is a token
 * endpoint rather than a proxy for consent traffic.
 */
@Controller("/")
@Requires(condition = Oid4VpConfiguration.Oid4VpCondition.class)
@ExecuteOn(TaskExecutors.BLOCKING)
@RequiredArgsConstructor
@Slf4j
public class InternalTokenController implements TokensApi {

    private final Oid4VpTokenService tokenService;

    /** {@inheritDoc} */
    @Override
    public HttpResponse<TokenResponseVO> createToken(TokenRequestVO tokenRequestVO) {
        if (tokenRequestVO == null || tokenRequestVO.getAudience() == null
                || tokenRequestVO.getAudience().isBlank()) {
            log.warn("Token requested without an audience.");
            return HttpResponse.badRequest();
        }
        String audience = tokenRequestVO.getAudience();
        try {
            AccessToken token = tokenService.tokenFor(audience);
            return HttpResponse.ok(new TokenResponseVO()
                    .accessToken(token.value())
                    .tokenType(token.tokenType())
                    .expiresIn(token.expiresInSeconds()));
        } catch (UnknownAudienceException unknownAudienceException) {
            log.warn("Rejected token request: {}", unknownAudienceException.getMessage());
            return HttpResponse.badRequest();
        } catch (TokenAcquisitionException tokenAcquisitionException) {
            log.error("Could not obtain a token for audience {}: {}", audience,
                    tokenAcquisitionException.getMessage(), tokenAcquisitionException);
            return HttpResponse.status(statusFor(tokenAcquisitionException.getReason()));
        }
    }

    /**
     * Maps a failure reason onto the status the caller sees. The split matters: the consent-plugin
     * fails closed without a token, so it must be able to tell "retry shortly" ({@code 502}) from
     * "this needs an operator" ({@code 403}/{@code 500}).
     */
    private static HttpStatus statusFor(TokenAcquisitionException.Reason reason) {
        return switch (reason) {
            case VERIFIER_UNREACHABLE -> HttpStatus.BAD_GATEWAY;
            case CREDENTIAL_REJECTED -> HttpStatus.FORBIDDEN;
            case MISCONFIGURED -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
