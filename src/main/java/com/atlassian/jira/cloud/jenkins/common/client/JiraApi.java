package com.atlassian.jira.cloud.jenkins.common.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.io.IOException;
import java.io.NotSerializableException;
import java.util.Objects;

/** Common HTTP client to talk to Jira Build and Deployment APIs in Jira */
public class JiraApi {

    private static final Logger log = LoggerFactory.getLogger(JiraApi.class);
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private String apiEndpoint;

    @Inject
    public JiraApi(
            final OkHttpClient httpClient, final ObjectMapper objectMapper, final String apiUrl) {
        this.httpClient = Objects.requireNonNull(httpClient);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.apiEndpoint = apiUrl;
    }

    /**
     * Submits an update to the Atlassian Builds or Deployments API and returns the response
     *
     * @param <ResponseEntity> Response entity, which can be either BuildApiResponse or
     *     DeploymentApiResponse
     * @param cloudId Jira Cloud Id
     * @param accessToken Access token generated from Atlassian API
     * @param jiraSiteUrl Jira site URL
     * @param jiraRequest An assembled payload to be submitted to Jira
     * @return Response from the API
     */
    public <ResponseEntity> PostUpdateResult<ResponseEntity> postUpdate(
            final String cloudId,
            final String accessToken,
            final String jiraSiteUrl,
            final JiraRequest jiraRequest,
            final Class<ResponseEntity> responseClass) {
        try {
            final String requestPayload = objectMapper.writeValueAsString(jiraRequest);
            final Request request = getRequest(cloudId, accessToken, requestPayload);
            final Response response = httpClient.newCall(request).execute();

            checkForErrorResponse(response);

            final ResponseEntity responseEntity = handleResponseBody(response, responseClass);
            return new PostUpdateResult<>(responseEntity);
        } catch (NotSerializableException e) {
            return handleError(String.format("Invalid JSON payload: %s", e.getMessage()));
        } catch (JsonProcessingException e) {
            return handleError(
                    String.format("Unable to create the request payload: %s", e.getMessage()));
        } catch (IOException e) {
            return handleError(
                    String.format(
                            "Server exception when submitting update to Jira: %s", e.getMessage()));
        } catch (ApiUpdateFailedException e) {
            return handleError(e.getMessage());
        } catch (Exception e) {
            return handleError(
                    String.format(
                            "Unexpected error when submitting update to Jira: %s", e.getMessage()));
        }
    }

    private void checkForErrorResponse(final Response response) throws IOException {
        if (!response.isSuccessful()) {
            final String message =
                    String.format(
                            "Error response code %d when submitting update to Jira",
                            response.code());
            final ResponseBody responseBody = response.body();
            if (responseBody != null) {
                log.error(
                        String.format(
                                "Error response body when submitting update to Jira: %s",
                                responseBody.string()));
                responseBody.close();
            }

            throw new ApiUpdateFailedException(message);
        }
    }

    private <ResponseEntity> ResponseEntity handleResponseBody(
            final Response response, final Class<ResponseEntity> responseClass) throws IOException {
        if (response.body() == null) {
            final String message = "Empty response body when submitting update to Jira";

            throw new ApiUpdateFailedException(message);
        }

        return objectMapper.readValue(
                response.body().bytes(),
                objectMapper.getTypeFactory().constructType(responseClass));
    }

    @VisibleForTesting
    void setApiEndpoint(final String apiEndpoint) {
        this.apiEndpoint = apiEndpoint;
    }

    private Request getRequest(
            final String cloudId, final String accessToken, final String requestPayload) {
        RequestBody body = RequestBody.create(JSON, requestPayload);
        return new Request.Builder()
                .url(String.format(this.apiEndpoint, cloudId))
                .addHeader("Authorization", "Bearer " + accessToken)
                .post(body)
                .build();
    }

    private <T> PostUpdateResult<T> handleError(final String errorMessage) {
        return new PostUpdateResult<>(errorMessage);
    }
}