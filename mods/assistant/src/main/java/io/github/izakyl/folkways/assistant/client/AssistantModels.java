package io.github.izakyl.folkways.assistant.client;

import io.github.izakyl.folkways.assistant.AssistantMod;
import io.github.izakyl.folkways.assistant.tool.ConfirmedAction;
import io.github.izakyl.jpi.agent.AgentTool;
import io.github.izakyl.jpi.ai.AiProvider;
import io.github.izakyl.jpi.ai.AnthropicMessages;
import io.github.izakyl.jpi.ai.ApiKeyResolver;
import io.github.izakyl.jpi.ai.GoogleGenerativeAi;
import io.github.izakyl.jpi.ai.Model;
import io.github.izakyl.jpi.ai.Models;
import io.github.izakyl.jpi.ai.OpenAiCompat;
import io.github.izakyl.jpi.ai.StreamOptions;
import io.github.izakyl.jpi.auth.Credential;
import io.github.izakyl.jpi.mc.JpiMini;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Provider-specific model and authentication choices stay outside conversation and panel lifetimes.
final class AssistantModels {

    record Runtime(Model model, StreamOptions options, AiProvider provider) {
    }

    interface Factory {

        Runtime create(AssistantSettings settings);
    }

    private static final Map<String, Factory> FACTORIES = new HashMap<>();

    static {
        register("openai-compatible", AssistantModels::completions);
        register("deepseek", AssistantModels::deepseek);
        register("anthropic", settings -> catalog(settings, AnthropicMessages.API));
        register("google", settings -> catalog(settings, GoogleGenerativeAi.API));
    }

    private AssistantModels() {
    }

    static void register(String provider, Factory factory) {
        if (FACTORIES.putIfAbsent(provider, factory) != null) {
            throw new IllegalArgumentException("Provider already registered: " + provider);
        }
    }

    static List<AgentTool> tools(boolean privileged, ConfirmedAction.Transport transport,
            ConfirmedAction.Confirmation confirmation, ConfirmedAction.Capture capture) {
        return List.of(new ConfirmedAction(privileged, transport, confirmation, capture));
    }

    static Runtime create(AssistantSettings settings) {
        Factory factory = FACTORIES.get(settings.provider());
        if (factory == null) {
            throw new IllegalArgumentException("Unknown assistant provider: " + settings.provider());
        }
        return factory.create(settings);
    }

    private static Runtime deepseek(AssistantSettings settings) {
        Model model = model(settings).provider("deepseek").reasoning(true)
                .compat(OpenAiCompat.deepseek()).build();
        // The cap covers the model's reasoning as well as its answer: a long working-out must not cut off the turn.
        return new Runtime(model, options()
                .apiKeyResolver(keyResolver(settings)).maxTokens(32768).build(),
                JpiMini.providers());
    }

    private static Runtime catalog(AssistantSettings settings, String api) {
        var builder = model(settings).provider(settings.provider()).api(api);
        Models.get(settings.provider(), settings.model())
                .filter(candidate -> candidate.baseUrl().equals(settings.baseUrl()))
                .ifPresent(candidate -> builder.name(candidate.name()).reasoning(candidate.reasoning())
                        .contextWindow(candidate.contextWindow()).maxTokens(candidate.maxTokens())
                        .cost(candidate.cost()).headers(candidate.headers()).compat(candidate.compat()));
        Model model = builder.build();
        return new Runtime(model, options().apiKeyResolver(keyResolver(settings))
                .maxTokens(4096).build(), JpiMini.providers());
    }

    static String credentialKey(String id) {
        return AssistantMod.ID + ".entry." + id;
    }

    static void removeCredentials(Set<String> ids) throws IOException {
        for (String id : ids) {
            JpiMini.credentials().remove(credentialKey(id));
        }
    }

    private static ApiKeyResolver keyResolver(AssistantSettings settings) {
        return model -> {
            var store = JpiMini.credentials();
            var credential = settings.id() == null ? null : store.get(credentialKey(settings.id())).orElse(null);
            return credential instanceof Credential.ApiKey key ? key.apiKey() : null;
        };
    }

    private static StreamOptions.Builder options() {
        return StreamOptions.builder().maxRetries(2).requestTimeoutMs(60000).idleTimeoutMs(60000);
    }

    private static Model.Builder model(AssistantSettings settings) {
        return Model.builder(settings.model(), settings.baseUrl())
                .input(settings.imageInput() ? List.of("text", "image") : List.of("text"));
    }

    private static Runtime completions(AssistantSettings settings) {
        var compat = OpenAiCompat.builder().maxTokensField("max_tokens").supportsDeveloperRole(false)
                .supportsStore(false).supportsReasoningEffort(false).build();
        Model model = model(settings).provider(settings.provider())
                .compat(compat).build();
        StreamOptions options = options().apiKeyResolver(keyResolver(settings))
                .maxTokens(1024).build();
        return new Runtime(model, options, JpiMini.providers());
    }
}
