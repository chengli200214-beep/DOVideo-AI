FROM golang:1.24-bookworm AS build

# Official upstream source for RELEASE.2025-09-07T16-13-09Z, pinned to its peeled commit.
ADD https://codeload.github.com/minio/minio/tar.gz/07c3a429bfed433e49018cb0f78a52145d4bedeb /tmp/minio-source.tar.gz
WORKDIR /src
RUN tar -xzf /tmp/minio-source.tar.gz --strip-components=1 -C /src
ENV CGO_ENABLED=0
RUN go build -trimpath -ldflags="-s -w" -o /out/minio .

FROM debian:bookworm-slim
COPY --from=build /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/ca-certificates.crt
COPY --from=build /out/minio /usr/local/bin/minio
EXPOSE 9000 9001
ENTRYPOINT ["/usr/local/bin/minio"]
CMD ["server", "/data"]
