## REST API

FIXME New data model.

Get current message:

```shell script
curl http://0.0.0.0:8080/hello
```

Post new message:

```shell script
curl -X POST -H "Content-Type: text/plain" -d "Hello @ $(date)" http://0.0.0.0:8080/hello
```