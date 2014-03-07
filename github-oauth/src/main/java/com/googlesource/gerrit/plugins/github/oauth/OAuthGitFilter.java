// Copyright (C) 2013 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.googlesource.gerrit.plugins.github.oauth;

import static com.google.gerrit.reviewdb.client.AccountExternalId.SCHEME_USERNAME;
import static javax.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static javax.servlet.http.HttpServletResponse.SC_UNAUTHORIZED;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang.StringUtils;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.slf4j.LoggerFactory;

import com.google.common.base.Objects;
import com.google.common.base.Strings;
import com.google.common.collect.Iterators;
import com.google.common.collect.Sets;
import com.google.gerrit.extensions.restapi.ResourceNotFoundException;
import com.google.gerrit.httpd.WebSession;
import com.google.gerrit.httpd.XGerritAuth;
import com.google.gerrit.reviewdb.client.AccountExternalId;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.server.AccessPath;
import com.google.gerrit.server.account.AccountCache;
import com.google.gerrit.server.account.AccountException;
import com.google.gerrit.server.account.AccountManager;
import com.google.gerrit.server.account.AuthRequest;
import com.google.gerrit.server.account.AuthResult;
import com.google.gerrit.server.account.PutHttpPassword;
import com.google.gwtorm.server.OrmException;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.github.oauth.OAuthProtocol.AccessToken;
import com.googlesource.gerrit.plugins.github.oauth.OAuthProtocol.Scope;

@Singleton
public class OAuthGitFilter implements Filter {
  private static final String GITHUB_X_OAUTH_BASIC = "x-oauth-basic";
  private static final org.slf4j.Logger log = LoggerFactory
      .getLogger(OAuthGitFilter.class);
  public static final String GIT_REALM_NAME =
      "GitHub authentication for Gerrit Code Review";
  private static final String GIT_AUTHORIZATION_HEADER = "Authorization";
  private static final String GIT_AUTHENTICATION_BASIC = "Basic ";

  private final OAuthCache oauthCache;
  private final AccountCache accountCache;
  private final GitHubHttpProvider httpClientProvider;
  private final GitHubOAuthConfig config;
  private final OAuthCookieProvider cookieProvider;
  private final XGerritAuth xGerritAuth;

  public static class BasicAuthHttpRequest extends HttpServletRequestWrapper {
    private HashMap<String, String> headers = new HashMap<String, String>();

    public BasicAuthHttpRequest(HttpServletRequest request, String username,
        String password) {
      super(request);

      try {
        headers.put(
            GIT_AUTHORIZATION_HEADER,
            GIT_AUTHENTICATION_BASIC
                + Base64.encodeBase64String((username + ":" + password)
                    .getBytes(OAuthGitFilter.encoding(request))));
      } catch (UnsupportedEncodingException e) {
        // This cannot really happen as we have already used the encoding for
        // decoding the request
      }
    }

    @Override
    public Enumeration<String> getHeaderNames() {
      final Enumeration<String> wrappedHeaderNames = super.getHeaderNames();
      HashSet<String> headerNames = new HashSet<String>(headers.keySet());
      while (wrappedHeaderNames.hasMoreElements()) {
        headerNames.add(wrappedHeaderNames.nextElement());
      }
      return Iterators.asEnumeration(headerNames.iterator());
    }

    @Override
    public String getHeader(String name) {
      String headerValue = headers.get(name);
      if (headerValue != null) {
        return headerValue;
      } else {
        return super.getHeader(name);
      }
    }
  }

  @Inject
  public OAuthGitFilter(OAuthCache oauthCache, AccountCache accountCache,
      GitHubHttpProvider httpClientProvider, GitHubOAuthConfig config,
      XGerritAuth xGerritAuth) {
    this.oauthCache = oauthCache;
    this.accountCache = accountCache;
    this.httpClientProvider = httpClientProvider;
    this.config = config;
    this.cookieProvider = new OAuthCookieProvider(TokenCipher.get());
    this.xGerritAuth = xGerritAuth;
  }

  @Override
  public void init(FilterConfig filterConfig) throws ServletException {
  }

  @Override
  public void doFilter(ServletRequest request, ServletResponse response,
      FilterChain chain) throws IOException, ServletException {

    HttpServletRequest httpRequest = (HttpServletRequest) request;
    HttpServletResponse httpResponse =
        new OAuthGitWrappedResponse((HttpServletResponse) response);
    log.debug("OAuthGitFilter(" + httpRequest.getRequestURL() + ") code="
        + request.getParameter("code"));

    OAuthCookie oAuthCookie =
        getAuthenticationCookieFromGitRequestUsingOAuthToken(httpRequest,
            httpResponse);
    if (oAuthCookie == null) {
      return;
    }
    String gerritPassword =
        oAuthCookie == OAuthCookie.ANONYMOUS ? null : accountCache
            .getByUsername(oAuthCookie.user).getPassword(oAuthCookie.user);

    if (gerritPassword == null && oAuthCookie != OAuthCookie.ANONYMOUS) {
      gerritPassword =
          generateRandomGerritPassword(oAuthCookie, httpRequest, httpResponse,
              chain);
      httpResponse.sendRedirect(getRequestPathWithQueryString(httpRequest));
      return;
    }

    if (oAuthCookie != OAuthCookie.ANONYMOUS) {
      httpRequest =
          new BasicAuthHttpRequest(httpRequest, oAuthCookie.user,
              gerritPassword);
    }

    chain.doFilter(httpRequest, httpResponse);
  }

  private String getRequestPathWithQueryString(HttpServletRequest httpRequest) {
    String requestPathWithQueryString =
        httpRequest.getContextPath() + httpRequest.getServletPath()
            + Strings.nullToEmpty(httpRequest.getPathInfo()) + "?"
            + httpRequest.getQueryString();
    return requestPathWithQueryString;
  }

  private String generateRandomGerritPassword(OAuthCookie oAuthCookie,
      HttpServletRequest httpRequest, HttpServletResponse httpResponse,
      FilterChain chain) throws IOException, ServletException {
    log.warn("User " + oAuthCookie.user + " has not a Gerrit HTTP password: "
        + "generating a random one in order to be able to use Git over HTTP");
    Cookie gerritCookie =
        getGerritLoginCookie(oAuthCookie.user, httpRequest, httpResponse, chain);
    String xGerritAuthValue = xGerritAuth.getAuthValue(gerritCookie);

    HttpPut putRequest =
        new HttpPut(getRequestUrlWithAlternatePath(httpRequest,
            "/accounts/self/password.http"));
    putRequest.setHeader("Cookie",
        gerritCookie.getName() + "=" + gerritCookie.getValue() + "; "
            + oAuthCookie.getName() + "=" + oAuthCookie.getValue());
    putRequest.setHeader(XGerritAuth.X_GERRIT_AUTH, xGerritAuthValue);

    putRequest.setEntity(new StringEntity("{\"generate\":true}",
        ContentType.APPLICATION_JSON));
    HttpResponse putResponse = httpClientProvider.get().execute(putRequest);
    if (putResponse.getStatusLine().getStatusCode() != HttpStatus.SC_OK) {
      throw new ServletException(
          "Cannot generate HTTP password for authenticating user "
              + oAuthCookie.user);
    }

    return accountCache.getByUsername(oAuthCookie.user).getPassword(
        oAuthCookie.user);
  }

  private URI getRequestUrlWithAlternatePath(HttpServletRequest httpRequest,
      String alternatePath) throws MalformedURLException {
    URL originalUrl = new URL(httpRequest.getRequestURL().toString());
    String contextPath = httpRequest.getContextPath();
    return URI.create(originalUrl.getProtocol() + "://" + originalUrl.getHost()
        + ":" + getPort(originalUrl) + contextPath + alternatePath);
  }

  private int getPort(URL originalUrl) {
    String protocol = originalUrl.getProtocol().toLowerCase();
    int port = originalUrl.getPort();
    if (port == -1) {
      return protocol.equals("https") ? 443 : 80;
    } else {
      return port;
    }
  }

  private Cookie getGerritLoginCookie(String username,
      HttpServletRequest httpRequest, HttpServletResponse httpResponse,
      FilterChain chain) throws IOException, ServletException {
    AuthenticatedPathHttpRequest loginRequest =
        new AuthenticatedLoginHttpRequest(httpRequest, config.httpHeader,
            username);
    AuthenticatedLoginHttpResponse loginResponse =
        new AuthenticatedLoginHttpResponse(httpResponse);
    chain.doFilter(loginRequest, loginResponse);
    return loginResponse.getGerritCookie();
  }

  private OAuthCookie getAuthenticationCookieFromGitRequestUsingOAuthToken(
      HttpServletRequest req, HttpServletResponse rsp) throws IOException {
    final String httpBasicAuth = getHttpBasicAuthenticationHeader(req);
    if (httpBasicAuth == null) {
      return OAuthCookie.ANONYMOUS;
    }

    if (isInvalidHttpAuthenticationHeader(httpBasicAuth)) {
      rsp.sendError(SC_UNAUTHORIZED);
      return null;
    }

    String oauthToken = StringUtils.substringBefore(httpBasicAuth, ":");
    String oauthKeyword = StringUtils.substringAfter(httpBasicAuth, ":");
    if (Strings.isNullOrEmpty(oauthToken)
        || Strings.isNullOrEmpty(oauthKeyword)) {
      rsp.sendError(SC_UNAUTHORIZED);
      return null;
    }

    if (!oauthKeyword.equalsIgnoreCase(GITHUB_X_OAUTH_BASIC)) {
      return OAuthCookie.ANONYMOUS;
    }

    boolean loginSuccessful = false;
    String oauthLogin = null;
    try {
      oauthLogin =
          oauthCache.getLoginByAccessToken(new AccessToken(oauthToken));
      loginSuccessful = !Strings.isNullOrEmpty(oauthLogin);
    } catch (ExecutionException e) {
      log.warn("Login failed for OAuth token " + oauthToken, e);
      loginSuccessful = false;
    }

    if (!loginSuccessful) {
      rsp.sendError(SC_FORBIDDEN);
      return null;
    }

    return cookieProvider.getFromUser(oauthLogin, "", "", new TreeSet<Scope>());
  }


  private boolean isInvalidHttpAuthenticationHeader(String usernamePassword) {
    return usernamePassword.indexOf(':') < 1;
  }

  static String encoding(HttpServletRequest req) {
    return Objects.firstNonNull(req.getCharacterEncoding(), "UTF-8");
  }

  private String getHttpBasicAuthenticationHeader(final HttpServletRequest req)
      throws UnsupportedEncodingException {
    String hdr = req.getHeader(GIT_AUTHORIZATION_HEADER);
    if (hdr == null || !hdr.startsWith(GIT_AUTHENTICATION_BASIC)) {
      return null;
    } else {
      return new String(Base64.decodeBase64(hdr
          .substring(GIT_AUTHENTICATION_BASIC.length())), encoding(req));
    }
  }

  @Override
  public void destroy() {
    log.info("Destroy");
  }
}
