package de.itrupp.p8.keycloak.authenticator;

import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
import org.keycloak.Config.Scope;
import org.keycloak.authentication.FormAction;
import org.keycloak.authentication.FormActionFactory;
import org.keycloak.authentication.FormContext;
import org.keycloak.authentication.ValidationContext;
import org.keycloak.connections.httpclient.HttpClientProvider;
import org.keycloak.events.Details;
import org.keycloak.events.Errors;
import org.keycloak.forms.login.LoginFormsProvider;
import org.keycloak.models.*;
import org.keycloak.models.AuthenticationExecutionModel.Requirement;
import org.keycloak.models.utils.FormMessage;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.services.ServicesLogger;
import org.keycloak.services.messages.Messages;
import org.keycloak.services.validation.Validation;
import org.keycloak.util.JsonSerialization;

import jakarta.ws.rs.core.MultivaluedMap;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public class RegistrationhCaptcha implements FormAction, FormActionFactory {
    public static final String H_CAPTCHA_RESPONSE = "h-captcha-response";
    public static final String HCAPTCHA_REFERENCE_CATEGORY = "hcaptcha";
    public static final String SITE_KEY = "site.key";
    public static final String SITE_SECRET = "secret";

    public static final String PROVIDER_ID = "registration-hcaptcha-action";

    @Override
    public void close() {

    }

    @Override
    public FormAction create(KeycloakSession session) {
        return this;
    }

    @Override
    public void init(Scope config) {

    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {

    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public String getDisplayType() {
        return "hCaptcha";
    }

    @Override
    public String getReferenceCategory() {
        return HCAPTCHA_REFERENCE_CATEGORY;
    }

    @Override
    public boolean isConfigurable() {
        return true;
    }

    private static AuthenticationExecutionModel.Requirement[] REQUIREMENT_CHOICES = {
            AuthenticationExecutionModel.Requirement.REQUIRED,
            AuthenticationExecutionModel.Requirement.DISABLED
    };
    @Override
    public Requirement[] getRequirementChoices() {
        return REQUIREMENT_CHOICES;
    }

    @Override
    public boolean isUserSetupAllowed() {
        return false;
    }

    @Override
    public String getHelpText() {
        return "Adds hCaptcha button.  hCaptchas verify that the entity that is registering is a human.  This can only be used on the internet and must be configured after you add it.";
    }


    @Override
    public void buildPage(FormContext context, LoginFormsProvider form) {
        AuthenticatorConfigModel captchaConfig = context.getAuthenticatorConfig();
        String userLanguageTag = context.getSession().getContext().resolveLocale(context.getUser()).toLanguageTag();

        if (captchaConfig == null || captchaConfig.getConfig() == null
                || captchaConfig.getConfig().get(SITE_KEY) == null
                || captchaConfig.getConfig().get(SITE_SECRET) == null
                ) {
            form.addError(new FormMessage(null, Messages.RECAPTCHA_NOT_CONFIGURED));
            return;
        }

        String siteKey = captchaConfig.getConfig().get(SITE_KEY);
        String compact = captchaConfig.getConfig().get("compact");
        form.setAttribute("hcaptchaRequired", true);
        form.setAttribute("hcaptchaCompact", compact);
        form.setAttribute("hcaptchaSiteKey", siteKey);
        form.addScript("https://js.hcaptcha.com/1/api.js?hl=" + userLanguageTag);

    }

    @Override
    public void validate(ValidationContext context) {

        MultivaluedMap<String, String> formData = context.getHttpRequest().getDecodedFormParameters();
        List<FormMessage> errors = new ArrayList<>();
        boolean success = false;
        context.getEvent().detail(Details.REGISTER_METHOD, "form");

        String captcha = formData.getFirst(H_CAPTCHA_RESPONSE);
        if (!Validation.isBlank(captcha)) {
            AuthenticatorConfigModel captchaConfig = context.getAuthenticatorConfig();
            String secret = captchaConfig.getConfig().get(SITE_SECRET);

            success = validateRecaptcha(context, success, captcha, secret);
        }
        if (success) {
            context.success();
        } else {
            errors.add(new FormMessage(null, Messages.RECAPTCHA_FAILED));
            formData.remove(H_CAPTCHA_RESPONSE);
            context.error(Errors.INVALID_REGISTRATION);
            context.validationError(formData, errors);
            context.excludeOtherErrors();
            return;

        }

    }


    protected boolean validateRecaptcha(ValidationContext context, boolean success, String captcha, String secret) {
        CloseableHttpClient httpClient = context.getSession().getProvider(HttpClientProvider.class).getHttpClient();
        HttpPost post = new HttpPost("https://hcaptcha.com/siteverify");
        List<NameValuePair> formparams = new LinkedList<>();
        formparams.add(new BasicNameValuePair("secret", secret));
        formparams.add(new BasicNameValuePair("response", captcha));
        formparams.add(new BasicNameValuePair("remoteip", context.getConnection().getRemoteAddr()));
        try {
            UrlEncodedFormEntity form = new UrlEncodedFormEntity(formparams, "UTF-8");
            post.setEntity(form);
            try (CloseableHttpResponse response = httpClient.execute(post)) {
                InputStream content = response.getEntity().getContent();
                try {
                    @SuppressWarnings("rawtypes")
                    Map json = JsonSerialization.readValue(content, Map.class);
                    Object val = json.get("success");
                    success = Boolean.TRUE.equals(val);
                } finally {
                    EntityUtils.consumeQuietly(response.getEntity());
                }
            }
        } catch (Exception e) {
            ServicesLogger.LOGGER.recaptchaFailed(e);
        }
        return success;
    }

    @Override
    public void success(FormContext context) {

    }

    @Override
    public boolean requiresUser() {
        return false;
    }

    @Override
    public boolean configuredFor(KeycloakSession session, RealmModel realm, UserModel user) {
        return true;
    }

    @Override
    public void setRequiredActions(KeycloakSession session, RealmModel realm, UserModel user) {

    }

    private static final List<ProviderConfigProperty> CONFIG_PROPERTIES = new ArrayList<ProviderConfigProperty>();

    static {
        ProviderConfigProperty property;
        property = new ProviderConfigProperty();
        property.setName(SITE_KEY);
        property.setLabel("hCaptcha Site Key");
        property.setType(ProviderConfigProperty.STRING_TYPE);
        property.setHelpText("hCaptcha Site Key");
        CONFIG_PROPERTIES.add(property);
        property = new ProviderConfigProperty();
        property.setName(SITE_SECRET);
        property.setLabel("hCaptcha Secret");
        property.setType(ProviderConfigProperty.STRING_TYPE);
        property.setHelpText("hCaptcha Secret");
        CONFIG_PROPERTIES.add(property);
        property = new ProviderConfigProperty();
        property.setName("compact");
        property.setLabel("hCaptcha Compact");
        property.setType(ProviderConfigProperty.BOOLEAN_TYPE);
        property.setHelpText("Compact format");
        CONFIG_PROPERTIES.add(property);
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return CONFIG_PROPERTIES;
    }

}
