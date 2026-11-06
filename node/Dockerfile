# Built from the repository root, which `.dockerignore` trims to what this
# needs: `docker build -f node/Dockerfile -t maestro-node .`
FROM ubuntu:26.04 AS build

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates cmake g++ git make \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /src
COPY tools/CMakeLists.txt tools/
RUN cmake -S tools -B build/tools -DCMAKE_BUILD_TYPE=Release \
    && cmake --build build/tools --target flatc -j"$(nproc)"

COPY schema schema
COPY node node
COPY fixtures/parity fixtures/parity
RUN build/tools/bin/flatc --cpp --scoped-enums --gen-name-strings -o node/generated schema/*.fbs \
    && cmake -S node -B build/node -DCMAKE_BUILD_TYPE=RelWithDebInfo \
        -DMAESTRO_BUILD_TESTS=OFF -DMAESTRO_BUILD_BENCH=ON \
    && cmake --build build/node --target maestro-node maestro-bench -j"$(nproc)"

FROM ubuntu:26.04

# `tc`, which `tools/chaos.py` runs in the node's network namespace.
RUN apt-get update \
    && apt-get install -y --no-install-recommends iproute2 \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /src/build/node/maestro-node /src/build/node/maestro-bench /usr/local/bin/
# `maestro-bench /usr/local/share/maestro/parity` measures the search rate.
COPY --from=build /src/fixtures/parity /usr/local/share/maestro/parity

USER ubuntu
EXPOSE 25700/tcp 25701/tcp 25702/udp
ENTRYPOINT ["maestro-node"]
