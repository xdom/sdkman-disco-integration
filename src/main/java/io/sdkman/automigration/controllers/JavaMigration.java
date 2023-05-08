package io.sdkman.automigration.controllers;

import io.sdkman.automigration.adapters.VersionAdapter;
import io.sdkman.automigration.logic.Broker;
import io.sdkman.automigration.adapters.PackageAdapter;
import io.sdkman.automigration.logic.Package;
import io.sdkman.automigration.logic.Release;
import io.sdkman.automigration.http.FoojayClient;
import io.sdkman.automigration.http.SdkmanClient;
import io.sdkman.automigration.logic.Version;
import io.sdkman.automigration.properties.FoojayConfigurationProperties;
import io.sdkman.automigration.properties.SdkmanConfigurationProperties;
import io.sdkman.automigration.wire.in.PackageResponse;
import io.sdkman.automigration.wire.in.ResultIdsResponse;
import io.sdkman.automigration.wire.in.ResultPackageResponse;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class JavaMigration {

	private final Logger logger = Logger.getLogger(JavaMigration.class.getName());

	private final FoojayClient foojayClient;

	private final SdkmanClient sdkmanClient;

	private final FoojayConfigurationProperties foojayProperties;

	private final SdkmanConfigurationProperties sdkmanProperties;

	public JavaMigration(FoojayClient foojayClient, SdkmanClient sdkmanClient,
			FoojayConfigurationProperties foojayProperties, SdkmanConfigurationProperties sdkmanProperties) {
		this.foojayClient = foojayClient;
		this.sdkmanClient = sdkmanClient;
		this.foojayProperties = foojayProperties;
		this.sdkmanProperties = sdkmanProperties;
	}

	public void execute(Map<String, List<String>> queryParams, Boolean defaultCandidate) {
		var foojayQueryParams = Stream
				.concat(queryParams.entrySet().stream(), FoojayClient.defaultQueryParams.entrySet().stream())
				.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
          (left, right) -> left.isEmpty() ? right : left));

		var resultPackageResponse = this.foojayClient.queryPackages(this.foojayProperties.url(), foojayQueryParams);
		// @formatter:off
		resultPackageResponse.filter(payload -> payload.result() != null && !payload.result().isEmpty())
				.map(ResultPackageResponse::result)
				.stream()
				.flatMap(Collection::stream)
				.filter(Package::libericaFilenameDoesNotContainLite)
				.max(Comparator.comparing(PackageResponse::javaVersion))
				.ifPresent(packageResponse -> {
					var sdkmanVersion = Version.format(packageResponse);
					sdkmanVersion.ifPresent(processVersion(packageResponse, defaultCandidate));
				});
		// @formatter:on
	}

	private Consumer<String> processVersion(PackageResponse packageResponse, Boolean defaultCandidate) {
		return version -> {
			var sdkmanVendor = PackageAdapter.FOOJAY_SDKMAN_VENDOR_MAPPING.get(packageResponse.distribution());
			var sdkmanVersionWithVendor = "%s-%s".formatted(version, sdkmanVendor);
			findAndPublish(packageResponse, sdkmanVendor, version, sdkmanVersionWithVendor, defaultCandidate);
		};
	}

	private void findAndPublish(PackageResponse packageResponse, String sdkmanVendor, String version,
			String sdkmanVersionWithVendor, Boolean defaultCandidate) {
		logger.log(Level.INFO, "Processing {0}", sdkmanVersionWithVendor);
		var sdkmanBrokerPlatform = Broker.platform(packageResponse.operatingSystem(), packageResponse.architecture());
		var sdkmanReleasePlatform = Release.platform(packageResponse.operatingSystem(), packageResponse.architecture());

		boolean sdkmanVersionExists = this.sdkmanClient.findVersion(this.sdkmanProperties.broker().url(),
				sdkmanVersionWithVendor, sdkmanBrokerPlatform);
		if (sdkmanVersionExists) {
			logger.log(Level.INFO, "Version: {0} already exists for platform {1}",
					new Object[] { sdkmanVersionWithVendor, sdkmanBrokerPlatform });
			return;
		}
		var ephemeralResponse = this.foojayClient.queryUrl(packageResponse.links().pkgInfoUri());
		ephemeralResponse.filter(payload -> payload.result() != null && payload.result().size() == 1)
				.map(ResultIdsResponse::result).flatMap(idsResponses -> idsResponses.stream().findFirst())
				.ifPresent(idsResponse -> {
					var versionRequest = VersionAdapter.toVersionRequest(sdkmanVendor, version, sdkmanReleasePlatform,
							idsResponse, defaultCandidate);
					logger.log(Level.INFO, versionRequest.toString());
					var newVersionResponse = this.sdkmanClient.newVersion(this.sdkmanProperties.release().url(),
							versionRequest);
					newVersionResponse.ifPresent(response -> logger.log(Level.INFO, response));
				});
	}

}
