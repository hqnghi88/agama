# Stage 1: Use the official GAMA image as a base to grab all GAMA files
FROM gamaplatform/gama:latest AS gama-base

# Stage 2: Build the final unified image
FROM eclipse-temurin:21-jre-alpine

# Install Node.js
RUN apk add --no-cache nodejs npm bash

# Copy GAMA from the official image
COPY --from=gama-base /opt/gama-platform /opt/gama-platform

# Make gama-headless available on PATH
ENV PATH="/opt/gama-platform/headless:${PATH}"

# Setup Node.js app
WORKDIR /app
COPY package*.json ./
RUN npm install --production
COPY . .

# Render provides PORT env var; default to 3000 for local
ENV PORT=3000
EXPOSE 3000

CMD ["node", "index.js"]
