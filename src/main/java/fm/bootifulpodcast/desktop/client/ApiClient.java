package fm.bootifulpodcast.desktop.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import fm.bootifulpodcast.desktop.*;
import lombok.SneakyThrows;
import lombok.extern.log4j.Log4j2;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.util.Assert;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.io.File;
import java.net.SocketException;
import java.net.URI;
import java.nio.file.Files;
import java.util.Date;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Log4j2
public class ApiClient {

	private final PodcastArchiveBuilder podcastArchiveBuilder;

	private final AtomicBoolean connected = new AtomicBoolean(false);

	private final ObjectMapper objectMapper;

	private final ScheduledExecutorService executor;

	private final RestTemplate restTemplate;

	private final ApplicationEventPublisher publisher;

	private final String serverUrl, actuatorUrl;

	private final int monitorDelayInSeconds;

	/*
	 * the client will poll for the status of a given submission for as long as there's an
	 * entry (with the UID as the key) in this {@link Map}.
	 */
	private final Map<String, AtomicBoolean> pollMap = new ConcurrentHashMap<>();

	public ApiClient(PodcastArchiveBuilder podcastArchiveBuilder, String serverUrl, ObjectMapper om,
			ScheduledExecutorService executor, ApplicationEventPublisher publisher, RestTemplate restTemplate,
			int interval) {
		this.objectMapper = om;
		this.podcastArchiveBuilder = podcastArchiveBuilder;
		this.monitorDelayInSeconds = interval;
		this.executor = executor;
		this.restTemplate = restTemplate;
		this.publisher = publisher;

		Assert.hasText(serverUrl, "The server URL provided is null");
		this.serverUrl = serverUrl.endsWith("/") ? serverUrl.substring(0, serverUrl.length() - 1) : serverUrl;
		this.actuatorUrl = this.serverUrl + "/actuator/health";
		log.debug("going to monitor the Actuator health endpoint every " + interval + "s.");
		log.debug("The server URL is " + this.serverUrl + " and the actuator URL is " + this.actuatorUrl);
	}

	/*
	 * An event is published only once, as soon as the API is connected or disconnected.
	 * We can't afford to poll for the connection until other components managed by other
	 * controllers are able to respond, once the stage is ready.
	 */
	@EventListener(StageReadyEvent.class)
	public void stageIsReady() {
		this.executor.scheduleWithFixedDelay(this::monitorConnectedEndpoint, 0, this.monitorDelayInSeconds,
				TimeUnit.SECONDS);
	}

	@EventListener
	public void monitorStopRequested(PodcastProductionMonitoringStopEvent req) {
		this.pollMap.remove(req.getSource());
	}

	private void monitorConnectedEndpoint() {
		try {
			var response = this.restTemplate.getForEntity(this.actuatorUrl, String.class);
			var responseBody = response.getBody();
			log.debug("Response from Actuator status endpoint (" + this.actuatorUrl + "): " + responseBody);
			Map<String, Object> jsonMap = Objects
					.requireNonNull(objectMapper.readValue(responseBody, new TypeReference<Map<String, Object>>() {
					}));
			var status = (String) jsonMap.get("status");
			var isActuatorHealthy = response.getStatusCode().is2xxSuccessful() && status.equalsIgnoreCase("UP");
			if (isActuatorHealthy && this.connected.compareAndSet(false, true)) {
				publisher.publishEvent(new ApiConnectedEvent(buildApiStatus()));
			}
		}
		catch (Exception e) {
			if (e instanceof ResourceAccessException || e instanceof SocketException) {
				log.debug("Could not connect to " + this.actuatorUrl);
			}
			if (this.connected.compareAndSet(true, false)) {
				this.publisher.publishEvent(new ApiDisconnectedEvent(buildApiStatus()));
			}
		}
	}

	private ApiStatus buildApiStatus() {
		return new ApiStatus(new Date(), URI.create(this.serverUrl));
	}

	/*
	 * This method submits a new request. It the polls the status endpoint for any
	 * updates. Eventually, it'll return a URI which we then advertise. This new event
	 * forces the UI to show a download link. It's possible to publish an event and cancel
	 * the monitoring. In this case, the polling method will return a {@code null}. If
	 * it's {@code null}, then we publish an event to reset the UI by loading an empty
	 * podcast.
	 */
	@Async
	public void publish(String uid, String title, String description, File introduction, File interview, File photo) {
		this.publisher.publishEvent(new PodcastProductionStartedEvent(uid));
		var archive = this.createArchive(uid, title, description, introduction, interview, photo);
		try {
			Optional//
					.ofNullable(this.submitForProduction(uid, archive))//
					.ifPresentOrElse(uri -> this.publisher.publishEvent(new PodcastProductionCompletedEvent(uid, uri)),
							() -> this.publisher.publishEvent(new PodcastLoadEvent(new PodcastModel())));
			;
		}
		finally {
			Assert.isTrue(!archive.exists() || archive.delete(),
					"The file " + archive.getAbsolutePath() + " could not be deleted, but should be.");
		}
	}

	@SneakyThrows
	private File createArchive(String uuid, String title, String description, File intro, File interview, File photo) {
		var zip = Files.createTempFile("podcast-archive-" + uuid, ".zip").toFile();
		return this.podcastArchiveBuilder.createArchive(zip, uuid, title, description, intro, interview, photo);
	}

	private URI submitForProduction(String uid, File archive) {
		var headers = new HttpHeaders();
		headers.setContentType(MediaType.MULTIPART_FORM_DATA);
		var resource = new FileSystemResource(archive);
		var body = new LinkedMultiValueMap<String, Object>();
		body.add("file", resource);
		var requestEntity = new HttpEntity<MultiValueMap<String, Object>>(body, headers);
		var url = this.serverUrl + "/podcasts/" + uid;
		var response = this.restTemplate.postForEntity(url, requestEntity, String.class);
		var location = response.getHeaders().getLocation();
		Assert.notNull(location, "The location URI must be non-null");
		var uri = URI.create(this.serverUrl + location.getPath());
		this.pollMap.put(uid, new AtomicBoolean(true));
		return this.pollProductionStatus(uid, uri);
	}

	@SneakyThrows
	private URI pollProductionStatus(String uid, URI statusUrl) {
		var parameterizedTypeReference = new ParameterizedTypeReference<Map<String, String>>() {
		};
		while (this.pollMap.getOrDefault(uid, new AtomicBoolean(false)).get()) {
			log.debug("the pollMap had '" + uid + "' " + "as true");
			var result = this.restTemplate.exchange(statusUrl, HttpMethod.GET, null, parameterizedTypeReference);
			Assert.isTrue(result.getStatusCode().is2xxSuccessful(),
					"The HTTP request must return a valid 20x series HTTP status");
			var status = Objects.requireNonNull(result.getBody());
			var key = "media-url";
			if (status.containsKey(key)) {
				return URI.create(status.get(key));
			}
			else {
				var seconds = 10;
				TimeUnit.SECONDS.sleep(seconds);
				log.debug("Sleeping " + seconds + "s while checking the production status at '" + statusUrl + "'.");
			}
		}
		log.debug("the pollMap had '" + uid + "' " + "as false. Returning.");
		return null;
	}

}
