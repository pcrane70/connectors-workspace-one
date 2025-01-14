/*
 * Copyright © 2018 VMware, Inc. All Rights Reserved.
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.vmware.connectors.gitlab.pr;

import com.google.common.collect.ImmutableList;
import com.vmware.connectors.test.ControllerTestsBase;
import com.vmware.connectors.test.JsonNormalizer;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.match.MockRestRequestMatchers;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.any;
import static org.hamcrest.Matchers.is;
import static org.springframework.http.HttpHeaders.ACCEPT_LANGUAGE;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;
import static org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static uk.co.datumedge.hamcrest.json.SameJSONAs.sameJSONAs;

class GitlabPrControllerTest extends ControllerTestsBase {

    private static final String GITLAB_AUTH_TOKEN = "test-auth-token";

    @ParameterizedTest
    @ValueSource(strings = {
            "/cards/requests",
            "/api/v1/test-owner/test-repo/1234/approve",
            "/api/v1/test-owner/test-repo/1234/comment"})
    void testProtectedResource(String uri) throws Exception {
        testProtectedResource(POST, uri);
    }

    @Test
    void testDiscovery() throws Exception {
        testConnectorDiscovery();
    }

    @Test
    void testRegex() throws Exception {
        List<String> expected = ImmutableList.of(
                "https://gitlab.com/vmware/test-repo/merge_requests/15"
        );
        testRegex("merge_request_urls", fromFile("fake/regex/pr-email.txt"), expected);
    }

    private WebTestClient.ResponseSpec doPost(
            String path,
            MediaType contentType,
            String authToken,
            String content
    ) {
        return doPost(path, contentType, authToken, content, null);
    }

    private WebTestClient.ResponseSpec doPost(
            String path,
            MediaType contentType,
            String authToken,
            String content,
            String language
    )  {

        WebTestClient.RequestHeadersSpec<?> spec = webClient.post()
                .uri(path)
                 .contentType(contentType)
                .accept(APPLICATION_JSON)
                .header(X_BASE_URL_HEADER, mockBackend.url(""))
                .header("x-routing-prefix", "https://hero/connectors/gitlab-pr/")
                .headers(headers -> headers(headers, path))
                .syncBody(content);

        if (StringUtils.isNotBlank(authToken)) {
            spec = spec.header(X_AUTH_HEADER, "Bearer " + authToken);
        }

        if (StringUtils.isNotBlank(language)) {
            spec = spec.header(ACCEPT_LANGUAGE, language);
        }

        return spec.exchange();
    }

    private WebTestClient.ResponseSpec requestCards(String authToken, String content) {
        return requestCards(authToken, content, null);
    }

    private WebTestClient.ResponseSpec requestCards(String authToken, String content, String language) {
        return doPost(
                        "/cards/requests",
                        APPLICATION_JSON,
                        authToken,
                        content,
                        language
                );
    }

    private WebTestClient.ResponseSpec approve(String authToken, String sha) {
        return doPost(
                        "/api/v1/vmware/test-repo/99/approve",
                        APPLICATION_FORM_URLENCODED,
                        authToken,
                        sha == null ? "" : String.format("sha=%s", sha)
                );
    }

    private WebTestClient.ResponseSpec comment(String authToken, String message) {
        return doPost(
                        "/api/v1/vmware/test-repo/99/comment",
                        APPLICATION_FORM_URLENCODED,
                        authToken,
                        message == null ? "" : String.format("message=%s", message)
                );
    }

    /////////////////////////////
    // Request Cards
    /////////////////////////////

    @Test
    void testRequestCardsUnauthorized() throws Exception {
        mockBackend.expect(ExpectedCount.manyTimes(), requestTo(any(String.class)))
                .andRespond(withUnauthorizedRequest());

        requestCards(GITLAB_AUTH_TOKEN, fromFile("requests/valid/cards/card.json"))
                .expectStatus().isBadRequest()
                .expectHeader().valueEquals("X-Backend-Status", "401");
    }

    @Test
    void testRequestCardsAuthHeaderMissing() throws Exception {
        requestCards(null, fromFile("requests/valid/cards/card.json"))
                .expectStatus().isBadRequest();
    }

    @DisplayName("Card request success cases")
    @ParameterizedTest(name = "{index} ==> Language=''{0}''")
    @CsvSource({
            ", responses/success/cards/card.json",
            "xx, responses/success/cards/card_xx.json"})
    void testRequestCardsSuccess(String acceptLanguage, String responseFile) throws Exception {
        trainGitlabForCards();

        String body = requestCards(GITLAB_AUTH_TOKEN, fromFile("requests/valid/cards/card.json"), acceptLanguage)
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(APPLICATION_JSON)
                .returnResult(String.class)
                .getResponseBody()
                .collect(Collectors.joining())
                .map(JsonNormalizer::forCards)
                .block();
        body = body.replaceAll("[a-z0-9]{40,}", "test-hash");
        assertThat(body, sameJSONAs(fromFile(responseFile)).allowingAnyArrayOrdering());
    }

    private void trainGitlabForCards() throws Exception {
        mockBackend.expect(requestTo("/api/v4/projects/vmware%2Ftest-repo/merge_requests/1"))
                .andExpect(header(AUTHORIZATION, "Bearer " + GITLAB_AUTH_TOKEN))
                .andExpect(method(GET))
                .andRespond(withSuccess(fromFile("fake/cards/small-merged-pr.json"), APPLICATION_JSON));

        mockBackend.expect(requestTo("/api/v4/projects/vmware%2Ftest-repo/merge_requests/2"))
                .andExpect(header(AUTHORIZATION, "Bearer " + GITLAB_AUTH_TOKEN))
                .andExpect(method(GET))
                .andRespond(withSuccess(fromFile("fake/cards/small-open-pr.json"), APPLICATION_JSON));

        mockBackend.expect(requestTo("/api/v4/projects/vmware%2Ftest-repo/merge_requests/3"))
                .andExpect(header(AUTHORIZATION, "Bearer " + GITLAB_AUTH_TOKEN))
                .andExpect(method(GET))
                .andRespond(withSuccess(fromFile("fake/cards/big-closed-pr.json"), APPLICATION_JSON));

        mockBackend.expect(requestTo("/api/v4/projects/vmware%2Ftest-repo/merge_requests/0-not-found"))
                .andExpect(header(AUTHORIZATION, "Bearer " + GITLAB_AUTH_TOKEN))
                .andExpect(method(GET))
                .andRespond(withStatus(NOT_FOUND));
    }

    @Test
    void testRequestCardsEmptyPrUrlsSuccess() throws Exception {
        requestCards(GITLAB_AUTH_TOKEN, fromFile("requests/valid/cards/empty-pr-urls.json"))
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(APPLICATION_JSON)
                .expectBody().json(fromFile("responses/success/cards/no-results.json"));
    }

    @Test
    void testRequestCardsMissingPrUrlsSuccess() throws Exception {
        requestCards(GITLAB_AUTH_TOKEN, fromFile("requests/valid/cards/missing-pr-urls.json"))
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(APPLICATION_JSON)
                .expectBody().json(fromFile("responses/success/cards/no-results.json"));
    }

    @DisplayName("Card request invalid token cases")
    @ParameterizedTest(name = "{index} ==> ''{0}''")
    @CsvSource({
            "requests/invalid/cards/empty-tokens.json, responses/success/cards/no-results.json",
            "requests/invalid/cards/missing-tokens.json, responses/success/cards/no-results.json"
    })
    void testRequestCardsInvalidTokens(String reqFile, String resFile) throws Exception {
        requestCards(GITLAB_AUTH_TOKEN, fromFile(reqFile))
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(APPLICATION_JSON)
                .expectBody().json(fromFile(resFile));
    }

    /////////////////////////////
    // Approve Action
    /////////////////////////////

    @Test
    void testApproveActionUnauthorized() {
        mockBackend.expect(requestTo(any(String.class)))
                .andRespond(withUnauthorizedRequest());

        approve(GITLAB_AUTH_TOKEN, "test-sha")
                .expectStatus().isBadRequest()
                .expectHeader().valueEquals("X-Backend-Status", "401");
    }

    @Test
    void testApproveAuthHeaderMissing() {
        approve(null, "test-sha")
                .expectStatus().isBadRequest();
    }

    @Test
    void testApproveActionMissingSha() {
        approve(GITLAB_AUTH_TOKEN, null)
                .expectStatus().isBadRequest();
    }

    @Test
    void testApproveActionSuccess() throws Exception {
        String fakeResponse = fromFile("fake/actions/approve/success.json");

        String expected = fromFile("responses/actions/approve/success.json");

        mockBackend.expect(requestTo("/api/v4/projects/vmware%2Ftest-repo/merge_requests/99/approve"))
                .andExpect(header(AUTHORIZATION, "Bearer " + GITLAB_AUTH_TOKEN))
                .andExpect(method(POST))
                .andExpect(MockRestRequestMatchers.content().contentType(APPLICATION_JSON))
                .andExpect(MockRestRequestMatchers.jsonPath("$.sha", is("test-sha")))
                .andRespond(withSuccess(fakeResponse, APPLICATION_JSON));

        approve(GITLAB_AUTH_TOKEN, "test-sha")
                .expectStatus().isOk()
                .expectBody().json(expected);
    }

    @Test
    void testApproveActionFailed() throws Exception {
        String fakeResponse = fromFile("fake/actions/approve/failed.json");

        String expected = fromFile("responses/actions/approve/failed.json");

        mockBackend.expect(requestTo("/api/v4/projects/vmware%2Ftest-repo/merge_requests/99/approve"))
                .andExpect(header(AUTHORIZATION, "Bearer " + GITLAB_AUTH_TOKEN))
                .andExpect(method(POST))
                .andExpect(MockRestRequestMatchers.content().contentType(APPLICATION_JSON))
                .andExpect(MockRestRequestMatchers.jsonPath("$.sha", is("test-sha")))
                .andRespond(
                        /*
                         * Unfortunately, not having the enterprise license causes /approve to
                         * 401 Unauthorized instead of 403 Forbidden.  This makes it impossible
                         * for us to distinguish the difference between "try again after you log in again"
                         * and "you will never be able to do this unless you buy a subscription".
                         */
                        withStatus(UNAUTHORIZED)
                                .contentType(APPLICATION_JSON)
                                .body(fakeResponse)
                );

        approve(GITLAB_AUTH_TOKEN, "test-sha")
                .expectStatus().isBadRequest()
                .expectHeader().valueEquals("x-backend-status", "401")
                .expectBody().json(expected);
    }

    /////////////////////////////
    // Comment Action
    /////////////////////////////

    @Test
    void testCommentActionUnauthorized() {
        mockBackend.expect(requestTo(any(String.class)))
                .andRespond(withUnauthorizedRequest());

        comment(GITLAB_AUTH_TOKEN, "test-comment")
                .expectStatus().isBadRequest()
                .expectHeader().valueEquals("X-Backend-Status", "401");
    }

    @Test
    void testCommentAuthHeaderMissing() {
        comment(null, "test-comment")
                .expectStatus().isBadRequest();
    }

    @Test
    void testCommentActionSuccess() throws Exception {
        String fakeResponse = fromFile("fake/actions/comment/success.json");

        String expected = fromFile("responses/actions/comment/success.json");

        mockBackend.expect(requestTo("/api/v4/projects/vmware%2Ftest-repo/merge_requests/99/notes"))
                .andExpect(header(AUTHORIZATION, "Bearer " + GITLAB_AUTH_TOKEN))
                .andExpect(method(POST))
                .andExpect(MockRestRequestMatchers.content().contentType(APPLICATION_JSON))
                .andExpect(MockRestRequestMatchers.jsonPath("$.body", is("test-comment")))
                .andRespond(withSuccess(fakeResponse, APPLICATION_JSON));

        comment(GITLAB_AUTH_TOKEN, "test-comment")
                .expectStatus().isOk()
                .expectBody().json(expected);
    }

    @Test
    void testCommentActionMissingComment() {
        comment(GITLAB_AUTH_TOKEN, null)
                .expectStatus().isBadRequest();
    }

    @Test
    void testCommentActionFailed() throws Exception {
        String fakeResponse = fromFile("fake/actions/comment/failed.json");

        String expected = fromFile("responses/actions/comment/failed.json");

        mockBackend.expect(requestTo("/api/v4/projects/vmware%2Ftest-repo/merge_requests/99/notes"))
                .andExpect(header(AUTHORIZATION, "Bearer " + GITLAB_AUTH_TOKEN))
                .andExpect(method(POST))
                .andExpect(MockRestRequestMatchers.content().contentType(APPLICATION_JSON))
                .andExpect(MockRestRequestMatchers.jsonPath("$.body", is("test-comment")))
                .andRespond(
                        withStatus(NOT_FOUND)
                                .contentType(APPLICATION_JSON)
                                .body(fakeResponse)
                );

        comment(GITLAB_AUTH_TOKEN, "test-comment")
                .expectStatus().is5xxServerError()
                .expectHeader().valueEquals("x-backend-status", "404")
                .expectBody().json(expected);
    }

}
