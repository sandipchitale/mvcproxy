package com.example.mvcproxy;

import java.io.IOException;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

import javax.servlet.http.HttpServletRequest;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.cloud.gateway.mvc.ProxyExchange;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.context.request.NativeWebRequest;

@SpringBootApplication
public class MvcproxyApplication {

	@RestController
	public static class HelloController {

		@GetMapping("/")
		public String hello() {
			return "hello";
		}

		@GetMapping("/zuul/restapi/**")
		public ResponseEntity<?> zuulProxyPath(HttpServletRequest httpServletRequest, ProxyExchange<byte[]> proxy)
				throws Exception {
			return proxyPathImpl(httpServletRequest, proxy, proxy.path("/zuul/restapi/"));
		}

		@GetMapping("/restapi/**")
		public ResponseEntity<?> proxyPath(HttpServletRequest httpServletRequest, ProxyExchange<byte[]> proxy)
				throws Exception {
			return proxyPathImpl(httpServletRequest, proxy, proxy.path("/restapi/"));
		}

		private static ResponseEntity<?> proxyPathImpl(HttpServletRequest httpServletRequest, ProxyExchange<byte[]> proxy, String path)
				throws Exception {
			ResponseEntity<byte[]> responseEntity = proxy.uri("https://reqres.in/" + path).get();
			System.out.println("Status: " + responseEntity.getStatusCodeValue());
			System.out.println("Headers:\n" + responseEntity.getHeaders());
			byte[] body = responseEntity.getBody();
			System.out.println("Body:\n" + new String(body));
			return responseEntity;
		}
	}


	@Component
	@Aspect
	public static class ProxyExchangeArgumentResolverAround {
		private static final String X_INFOARCHIVE_RESTAPI_PROXY_TIMEOUT = "x-infoarchive-restapi-proxy-timeout";

		private static ConcurrentHashMap<Duration, RestTemplate> durationToRestTemplateMap = new ConcurrentHashMap<>();

		@Around("execution(* org.springframework.cloud.gateway.mvc.config.ProxyExchangeArgumentResolver.resolveArgument(..)))")
		public Object customizeResolveArgument(ProceedingJoinPoint proceedingJoinPoint) throws Throwable
		{
			Object proxyExchange = proceedingJoinPoint.proceed();

			Object[] args = proceedingJoinPoint.getArgs();
			NativeWebRequest webRequest = (NativeWebRequest) args[2];
			String X_INFOARCHIVE_RESTAPI_PROXY_TIMEOUT_VALUE = webRequest
					.getHeader(X_INFOARCHIVE_RESTAPI_PROXY_TIMEOUT);
			if (X_INFOARCHIVE_RESTAPI_PROXY_TIMEOUT_VALUE != null) {
				Duration readTimeout =
					("-1".equals(X_INFOARCHIVE_RESTAPI_PROXY_TIMEOUT_VALUE)) ?
				Duration.ofMillis(Integer.MAX_VALUE) :
				Duration.ofMillis(Long.parseLong(X_INFOARCHIVE_RESTAPI_PROXY_TIMEOUT_VALUE));

				System.out.println(
						X_INFOARCHIVE_RESTAPI_PROXY_TIMEOUT + " is " + X_INFOARCHIVE_RESTAPI_PROXY_TIMEOUT_VALUE);

				Field restField = proxyExchange.getClass().getDeclaredField("rest");
				restField.setAccessible(true);

				// Cache the restTemplate
				RestTemplate restTemplate = durationToRestTemplateMap.get(readTimeout);
				if (restTemplate == null) {
					// Create custom RestTemplate
					RestTemplateBuilder builder = new RestTemplateBuilder();
					restTemplate = builder
						.setReadTimeout(readTimeout)
						.build();
					restTemplate.setErrorHandler(new NoOpResponseErrorHandler());
					restTemplate.getMessageConverters().add(new ByteArrayHttpMessageConverter() {
						@Override
						public boolean supports(Class<?> clazz) {
							return true;
						}
					});
					durationToRestTemplateMap.put(readTimeout, restTemplate);
				}
				restField.set(proxyExchange, restTemplate);
			} else {
				System.out.println(X_INFOARCHIVE_RESTAPI_PROXY_TIMEOUT + " not specified");
			}

			return proxyExchange;
		}

		private static class NoOpResponseErrorHandler extends DefaultResponseErrorHandler {
			@Override
			public void handleError(ClientHttpResponse response) throws IOException {
			}
		}
	}

	public static void main(String[] args) {
		SpringApplication.run(MvcproxyApplication.class, args);
	}

}
