#!/bin/bash
curl http://localhost:8080/v3/api-docs | python3 -m json.tool > ./openapi.json

# UI mode http://localhost:8080/swagger-ui/index.html
