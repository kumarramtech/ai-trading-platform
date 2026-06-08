# AI Trading Platform

## Overview

AI Trading Platform is a microservices-based application built using Spring Boot, Spring WebFlux, and Reactive Programming principles. The goal of this project is to learn and implement modern enterprise application architecture while building a platform capable of generating stock trading signals and AI-driven insights.

## Current Architecture

```text
Client
   ↓
API Gateway (8080)
   ↓
Signal Engine Service (8082)
   ↓
Stock Service (8081)
```

## Services

### API Gateway

* Entry point for all client requests
* Route management using Spring Cloud Gateway
* Centralized API access

### Stock Service

* Provides stock information
* Exposes stock-related APIs
* Simulates stock market data

### Signal Engine Service

* Consumes stock data from Stock Service
* Generates BUY / SELL / HOLD recommendations
* Implements reactive service-to-service communication using WebClient

## Technology Stack

* Java 21
* Spring Boot 3.5.4
* Spring WebFlux
* Spring Cloud Gateway
* Maven
* Lombok
* MySQL (Planned)
* Spring AI (Planned)
* AWS (Planned)
* Docker (Planned)

## Features Implemented

* Multi-module Maven project setup
* API Gateway configuration
* Reactive REST APIs using WebFlux
* Service-to-Service communication using WebClient
* Trading Signal generation
* Health endpoints

## Planned Features

### Phase 1

* Dynamic stock lookup
* Portfolio Service
* Notification Service
* MySQL integration

### Phase 2

* Technical indicators

  * SMA
  * EMA
  * RSI
  * MACD

### Phase 3

* AI-based stock analysis using Spring AI
* News sentiment analysis
* Trading recommendations

### Phase 4

* AWS deployment
* Docker containers
* CI/CD pipeline
* Monitoring and Observability

### Phase 5

* Automated trading execution
* Risk management engine
* Backtesting framework

## Running the Project

### Build

```bash
mvn clean install
```

### Start Services

1. Stock Service (8081)
2. Signal Engine Service (8082)
3. API Gateway (8080)

### Sample APIs

Get Stock Information:

```http
GET http://localhost:8081/stocks/TCS
```

Generate Trading Signal:

```http
GET http://localhost:8082/signals/TCS
```

Generate Trading Signal via Gateway:

```http
GET http://localhost:8080/signals/TCS
```

✔ Added latest technical indicator API
✔ Integrated Signal Engine with indicator service
✔ Replaced price-threshold strategy
✔ Implemented RSI, EMA20, EMA50 and MACD based scoring
✔ Added confidence score calculation
✔ Added BUY / SELL / HOLD decision engine
✔ Fixed SELL target and stop-loss calculation
✔ Implemented reactive WebFlux integration
✔ Completed signal monitoring and statistics APIs

## Learning Objectives

* Reactive Programming with Spring WebFlux
* Microservices Architecture
* API Gateway Pattern
* Service-to-Service Communication
* Spring AI Integration
* Cloud Native Development
* AWS Deployment

## Author

Ram Kumar

Personal Learning and Product Development Project
