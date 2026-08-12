(() => {
  const versionNodes = document.querySelectorAll("[data-maven-version]");
  if (versionNodes.length === 0) return;

  const repositoryBase =
    "https://maven.rohittp.com/com/rohittp/rentile";

  const artifacts = new Map();

  versionNodes.forEach((node) => {
    const artifact = node.dataset.mavenVersion;

    if (!artifacts.has(artifact)) {
      artifacts.set(artifact, []);
    }

    artifacts.get(artifact).push(node);
  });

  artifacts.forEach((nodes, artifact) => {
    const metadataUrl =
      `${repositoryBase}/${artifact}/maven-metadata.xml`;

    fetch(metadataUrl, { cache: "no-store" })
      .then((response) => {
        if (!response.ok) {
          throw new Error(
            `Metadata request failed with HTTP ${response.status}`
          );
        }

        return response.text();
      })
      .then((metadata) => {
        const xml = new DOMParser().parseFromString(
          metadata,
          "application/xml"
        );

        if (xml.querySelector("parsererror")) {
          throw new Error("Metadata response is not valid XML");
        }

        const version = xml
          .querySelector("versioning > release")
          ?.textContent
          ?.trim();

        if (!version) {
          throw new Error(
            "Metadata does not contain a release version"
          );
        }

        nodes.forEach((node) => {
          node.textContent = version;
        });
      })
      .catch((error) => {
        console.error(
          `Unable to load the published ${artifact} version.`,
          error
        );
      });
  });
})();
