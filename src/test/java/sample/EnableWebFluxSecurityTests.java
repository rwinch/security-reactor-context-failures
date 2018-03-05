/*
 * Copyright 2002-2017 the original author or authors.
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

package sample;

import org.junit.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.UserDetailsRepositoryReactiveAuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.AuthenticatedReactiveAuthorizationManager;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.userdetails.ReactiveUserDetailsService;
import org.springframework.security.web.server.MatcherSecurityWebFilterChain;
import org.springframework.security.web.server.ServerHttpBasicAuthenticationConverter;
import org.springframework.security.web.server.WebFilterChainProxy;
import org.springframework.security.web.server.authentication.AuthenticationWebFilter;
import org.springframework.security.web.server.authentication.HttpBasicServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authentication.ServerAuthenticationEntryPointFailureHandler;
import org.springframework.security.web.server.authorization.AuthorizationWebFilter;
import org.springframework.security.web.server.authorization.ExceptionTranslationWebFilter;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.web.reactive.function.client.ExchangeFilterFunctions.Credentials.basicAuthenticationCredentials;
import static org.springframework.web.reactive.function.client.ExchangeFilterFunctions.basicAuthentication;

/**
 * @author Rob Winch
 * @since 5.0
 */
public class EnableWebFluxSecurityTests {

	WebFilter springSecurityFilterChain = springSecurityWebFilterChainFilter();

	// gh-4831
	@Test
	public void defaultMediaAllThenUnAuthorized() {

		WebTestClient client = WebTestClient
			.bindToController(new Http200RestController())
			.webFilter(this.springSecurityFilterChain)
			.build();

		client.get()
			.uri("/")
			.accept(MediaType.ALL)
			.exchange()
			.expectStatus().isUnauthorized()
			.expectBody().isEmpty();
	}

	@Test
	public void defaultPopulatesReactorContextWhenAuthenticating() {
		WebTestClient client = WebTestClient
				.bindToController(new Http200RestController())
				.webFilter(this.springSecurityFilterChain,
			(exchange, chain) ->
				ReactiveSecurityContextHolder.getContext()
					.map(SecurityContext::getAuthentication)
					.flatMap( principal -> exchange.getResponse()
						.writeWith(Mono.just(toDataBuffer(principal.getName()))))
		)
		.configureClient()
		.filter(basicAuthentication())
		.build();

		client
			.get()
			.uri("/")
			.attributes(basicAuthenticationCredentials("user", "password"))
			.exchange()
			.expectStatus().isOk()
			.expectBody(String.class).consumeWith( result -> assertThat(result.getResponseBody()).isEqualTo("user"));
	}

	public WebFilter authenticationFilter() {
		AuthenticationWebFilter authenticationFilter = new AuthenticationWebFilter(authenticationManager());
		authenticationFilter.setAuthenticationFailureHandler(new ServerAuthenticationEntryPointFailureHandler(new HttpBasicServerAuthenticationEntryPoint()));
		authenticationFilter.setAuthenticationConverter(new ServerHttpBasicAuthenticationConverter());
		return authenticationFilter;
	}

	private WebFilter exceptionTranslation() {
		return new ExceptionTranslationWebFilter();
	}

	private WebFilter authorizationFilter() {
		return new AuthorizationWebFilter(AuthenticatedReactiveAuthorizationManager.authenticated());
	}

	public WebFilter springSecurityWebFilterChainFilter() {
		MatcherSecurityWebFilterChain chain = new MatcherSecurityWebFilterChain(e -> ServerWebExchangeMatcher.MatchResult.match(),
				Arrays.asList(authenticationFilter(), exceptionTranslation(), authorizationFilter()));
		return new WebFilterChainProxy(chain);
	}

	private ReactiveAuthenticationManager authenticationManager() {
		return a -> Mono.just(new UsernamePasswordAuthenticationToken("user", "password",
				AuthorityUtils.createAuthorityList("ROLE_USER")));
	}

	private static DataBuffer toDataBuffer(String body) {
		DataBuffer buffer = new DefaultDataBufferFactory().allocateBuffer();
		buffer.write(body.getBytes(StandardCharsets.UTF_8));
		return buffer;
	}

	@RestController
	public static class Http200RestController {
		@RequestMapping("/**")
		@ResponseStatus(HttpStatus.OK)
		public String ok() {
			return "ok";
		}
	}
}
