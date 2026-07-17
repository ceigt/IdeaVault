FROM golang:1.24-alpine AS build
WORKDIR /src
COPY go.mod go.sum main.go ./
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/ideavault-sync .

FROM alpine:3.21
RUN addgroup -S app && adduser -S -G app app && mkdir /data && chown app:app /data
COPY --from=build /out/ideavault-sync /usr/local/bin/ideavault-sync
USER app
EXPOSE 8080
ENTRYPOINT ["ideavault-sync"]