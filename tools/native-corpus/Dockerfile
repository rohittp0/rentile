FROM ubuntu:22.04

RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install --yes --no-install-recommends \
        ca-certificates \
        curl \
        libcurl4-openssl-dev \
        libfontconfig1 \
        libfreetype6 \
        libgl1 \
        libharfbuzz0b \
        libx11-6 \
        libxext6 \
        libxi6 \
        libxrender1 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /work

ENV RENTILE_NATIVE_CORPUS=1

ENTRYPOINT ["/work/test.kexe"]
CMD ["--ktest_logger=TEAMCITY", "--ktest_gradle_filter=com.rohittp.rentile.NativeMapCatalogCorpusSmokeTest.rendersCompletePublicCatalogOnNativeTarget"]
