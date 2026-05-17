# Customer Lookup Worker

[![Java Version](https://img.shields.io/badge/Java-11-blue.svg)](https://www.oracle.com/java/)
[![Maven](https://img.shields.io/badge/Maven-Project-orange.svg)](https://maven.apache.org/)
[![Camunda](https://img.shields.io/badge/Camunda-External%20Task%20Client-red.svg)](https://camunda.com/)

Ein Camunda External Task Worker, der Kundenreferenzen abonniert, Kundendaten über eine REST-API abruft und die Ergebnisse an den Camunda-Prozess zurückgibt.

## 📋 Inhaltsverzeichnis

- [Überblick](#überblick)
- [Architektur](#architektur)
  - [Design Patterns](#design-patterns)
  - [Komponenten](#komponenten)
- [Voraussetzungen](#voraussetzungen)
- [Installation](#installation)
- [Konfiguration](#konfiguration)
  - [Konfigurations-Parameter](#konfigurations-parameter-erklärt)
  - [Worker-Konfiguration](#worker-konfiguration)
  - [Umgebungs-Anpassungen](#anpassungen-für-andere-umgebungen)
- [Verwendung](#verwendung)
- [Projektstruktur](#projektstruktur)
- [Prozessablauf](#prozessablauf)
  - [Sequenzdiagramm](#sequenzdiagramm)
  - [Input/Output-Variablen](#input-variablen-von-camunda)
- [Code-Beispiele und Erklärungen](#-code-beispiele-und-erklärungen)
  - [Main-Klasse](#1-main-klasse-worker-initialisierung)
  - [Task Handler](#2-task-handler-hauptverarbeitungslogik)
  - [Service Layer](#3-service-layer-business-validierung)
  - [API Client](#4-api-client-rest-kommunikation)
  - [Retry-Mechanismus](#5-retry-mechanismus-erklärt)
  - [Datenfluss](#6-datenfluss-durch-die-schichten)
- [Fehlerbehandlung](#fehlerbehandlung)
  - [Business-Fehler](#1-business-fehler-fallback)
  - [Technische Fehler](#2-technische-fehler-retry)
  - [HTTP-Status-Codes](#3-http-status-code-behandlung)
- [Entwicklung](#entwicklung)
- [Beispiel-Szenarien](#-beispiel-szenarien)
  - [Erfolgreicher Lookup](#szenario-1-erfolgreicher-lookup-mit-vollständigen-daten)
  - [Unvollständige Daten](#szenario-2-unvollständige-kundendaten)
  - [404 Not Found](#szenario-3-kunde-nicht-gefunden-404)
  - [API Retry](#szenario-4-api-temporär-nicht-erreichbar-retry)
  - [Persistente Fehler](#szenario-5-persistenter-api-fehler-alle-retries-aufgebraucht)
  - [Fehlende Parameter](#szenario-6-fehlende-customerreference)
- [Abhängigkeiten](#abhängigkeiten)
  - [Dependency Tree](#dependency-tree)
  - [Verwendung im Code](#dependency-verwendung-im-code)
- [FAQ](#-faq-häufig-gestellte-fragen)
- [Troubleshooting](#-troubleshooting)
- [Support](#support)

## 🎯 Überblick

Der Customer Lookup Worker ist ein Microservice, der als externer Task-Handler für Camunda BPM fungiert. Er:

- Abonniert das Camunda-Topic `group6_customer_lookup`
- Ruft Kundendaten über eine REST-API ab
- Validiert die vollständigkeit der Kundendaten
- Gibt strukturierte Ergebnisse an den Camunda-Prozess zurück
- Behandelt Fehler mit automatischem Retry-Mechanismus

## 🏗️ Architektur

Das Projekt folgt einer sauberen, schichtenbasierten Architektur:

```
┌─────────────────────────────────────┐
│   CustomerLookupWorker (Main)       │
│   - Initialisierung                 │
│   - Konfiguration                   │
└─────────────┬───────────────────────┘
              │
┌─────────────▼───────────────────────┐
│  CustomerLookupExternalTaskHandler  │
│  - Task-Verarbeitung                │
│  - Fehlerbehandlung                 │
│  - Retry-Logik                      │
└─────────────┬───────────────────────┘
              │
┌─────────────▼───────────────────────┐
│    CustomerLookupService            │
│    - Business-Logik                 │
│    - Datenvalidierung               │
└─────────────┬───────────────────────┘
              │
┌─────────────▼───────────────────────┐
│      ClientDataApiClient            │
│      - REST-Kommunikation           │
│      - HTTP-Fehlerbehandlung        │
└─────────────────────────────────────┘
```

### Design Patterns

Das Projekt nutzt bewährte Design Patterns:

#### 1️⃣ **Dependency Injection (Constructor Injection)**
```java
public class CustomerLookupService {
    private final ClientDataApiClient apiClient;  // ← Immutable dependency
    
    // Dependency wird im Konstruktor übergeben
    public CustomerLookupService(ClientDataApiClient apiClient) {
        this.apiClient = apiClient;
    }
}
```

**Vorteile:**
- ✅ Testbarkeit (Mock-Objekte in Tests)
- ✅ Loose Coupling (lose Kopplung)
- ✅ Single Responsibility

---

#### 2️⃣ **Layered Architecture (Schichtenarchitektur)**
```
┌──────────────────────────────────────────┐
│  Presentation Layer  │  Handler          │  ← Camunda Integration
├──────────────────────────────────────────┤
│  Business Layer      │  Service          │  ← Geschäftslogik
├──────────────────────────────────────────┤
│  Integration Layer   │  Client           │  ← External API Calls
├──────────────────────────────────────────┤
│  Data Layer          │  DTO              │  ← Datenobjekte
└──────────────────────────────────────────┘
```

**Regeln:**
- Jede Schicht kennt nur die direkt darunterliegende Schicht
- Keine Skip-Level-Aufrufe (Handler → Service → Client)
- Klare Verantwortlichkeiten

---

#### 3️⃣ **Data Transfer Object (DTO)**
```java
public class CustomerData {
    private String customerReference;
    private String destination;
    private String recepientPhone;
    private String email;
    private String country;
    private boolean customerLookupSuccess;
    
    // Getters & Setters...
}
```

**Zweck:**
- Transport von Daten zwischen Schichten
- JSON-Serialisierung/Deserialisierung
- Trennung von Business-Objekten und Transfer-Objekten

---

#### 4️⃣ **Exception Handling Strategy**
```java
try {
    // Business operation
} catch (IllegalArgumentException | CustomerDataNotFoundException ex) {
    // Business errors → Fallback (kein Retry)
    completeWithFallback(...);
} catch (ProcessingException | Exception ex) {
    // Technical errors → Retry
    handleTechnicalError(...);
}
```

**Strategie:**
- Business-Fehler: Sofortiger Fallback
- Technische Fehler: Retry mit Exponential Backoff
- Klare Trennung der Fehlertypen

---

#### 5️⃣ **Builder Pattern (durch Camunda bereitgestellt)**
```java
ExternalTaskClient client = ExternalTaskClient.create()
        .baseUrl(CAMUNDA_BASE_URL)
        .asyncResponseTimeout(1000)
        .build();  // ← Builder Pattern

client.subscribe(TOPIC)
        .lockDuration(1000)
        .handler(handler)
        .open();  // ← Builder Pattern
```

**Vorteile:**
- Fluent API (lesbar)
- Optionale Parameter
- Unveränderliches Resultat-Objekt

### Komponenten

#### **CustomerLookupWorker**
- Haupteinstiegspunkt der Applikation
- Initialisiert alle Komponenten
- Konfiguriert den Camunda External Task Client

#### **CustomerLookupExternalTaskHandler**
- Implementiert die Camunda `ExternalTaskHandler`-Schnittstelle
- Orchestriert den Task-Verarbeitungsprozess
- Implementiert Retry-Strategie (3 Versuche, 60 Sekunden Timeout)
- Behandelt verschiedene Fehlertypen unterschiedlich

#### **CustomerLookupService**
- Enthält die Business-Logik für das Customer Lookup
- Validiert Eingabeparameter
- Prüft Vollständigkeit der Kundendaten
- Setzt das `customerLookupSuccess`-Flag

#### **ClientDataApiClient**
- Kapselt die HTTP-Kommunikation mit der Client Data API
- Nutzt JAX-RS Client (Jersey)
- Behandelt HTTP-Status-Codes
- Übersetzt HTTP-Fehler in Domain-Exceptions

#### **CustomerData (DTO)**
- Datenklasse für Kundeninformationen
- Enthält: customerReference, destination, recepientPhone, email, country, customerLookupSuccess

#### **CustomerDataNotFoundException**
- Custom Exception für nicht gefundene Kunden (HTTP 404)

## ✅ Voraussetzungen

- **Java 11** oder höher
- **Maven 3.6+**
- **Laufende Camunda BPM Engine** (erreichbar unter der konfigurierten URL)
- **Client Data API Service** (erreichbar unter der konfigurierten URL)

## 📦 Installation

### 1. Repository klonen (falls zutreffend)

```powershell
git clone <repository-url>
cd customer-lookup-worker
```

### 2. Projekt bauen

```powershell
mvn clean install
```

### 3. JAR erstellen

```powershell
mvn package
```

Die ausführbare JAR-Datei wird im `target`-Verzeichnis erstellt.

## ⚙️ Konfiguration

Die Konfiguration erfolgt über Konstanten in der Klasse `CustomerLookupWorker.java`:

```java
// Camunda BPM Engine URL (mit Authentifizierung)
private static final String CAMUNDA_BASE_URL = 
    "http://group6:p5TuHbjEadLeT6L@192.168.111.3:8080/engine-rest";

// Client Data API URL
private static final String CLIENT_DATA_API_URL = 
    "http://localhost:8082";

// Camunda Topic für External Tasks
private static final String TOPIC = 
    "group6_customer_lookup";
```

### Konfigurations-Parameter erklärt

```
┌─────────────────────────────────────────────────────────────────┐
│                     CAMUNDA_BASE_URL                            │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  http://group6:p5TuHbjEadLeT6L@192.168.111.3:8080/engine-rest  │
│  └──┬──┘ └──────┬──────────┘ └──────┬──────┘ └──┬──┘ └───┬──┘  │
│   Schema   Credentials        Host    Port    Context Path    │
│                                                                 │
│  • Schema: http oder https                                      │
│  • Credentials: user:password (Basic Auth)                      │
│  • Host: IP-Adresse oder Hostname der Camunda Engine          │
│  • Port: Standard 8080 für Camunda                             │
│  • Context: /engine-rest (Camunda REST API Endpunkt)          │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                  CLIENT_DATA_API_URL                            │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  http://localhost:8082                                          │
│  └──┬──┘ └────┬────┘ └┬─┘                                       │
│   Schema   Host    Port                                         │
│                                                                 │
│  • Base URL für die Client Data REST API                       │
│  • Pfad wird automatisch ergänzt: /api/customers/{reference}   │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                         TOPIC                                   │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  "group6_customer_lookup"                                       │
│                                                                 │
│  • Muss mit dem Topic im BPMN-Prozess übereinstimmen          │
│  • Naming Convention: {group}_{function}                        │
│  • Worker abonniert Tasks mit diesem Topic                     │
└─────────────────────────────────────────────────────────────────┘
```

### Worker-Konfiguration

```java
ExternalTaskClient client = ExternalTaskClient.create()
        .baseUrl(CAMUNDA_BASE_URL)     
        .asyncResponseTimeout(1000)    // ← Timeout für Long-Polling
        .build();

client.subscribe(TOPIC)
        .lockDuration(1000)            // ← Task-Lock in Millisekunden
        .handler(handler)
        .open();
```

**Parameter-Erklärung:**

| Parameter | Wert | Bedeutung |
|-----------|------|-----------|
| `asyncResponseTimeout` | 1000 ms | Wie lange wartet der Worker auf neue Tasks beim Polling |
| `lockDuration` | 1000 ms | Wie lange ist ein Task gesperrt während der Verarbeitung |

**Lock Duration visualisiert:**

```
T0: Task wird abgeholt
│
├─ Lock Duration: 1000ms
│  │
│  ├─ Worker verarbeitet Task
│  │
│  └─ Task ist für andere Worker gesperrt
│
T1: Lock läuft ab
│
└─ Falls noch nicht complete: Task wird wieder verfügbar
```

### Anpassungen für andere Umgebungen

Für produktive Umgebungen sollten diese Werte externalisiert werden (z.B. über Umgebungsvariablen, Properties-Dateien oder ConfigMaps).

#### Option 1: Umgebungsvariablen

```java
public class CustomerLookupWorker {
    private static final String CAMUNDA_BASE_URL = 
        System.getenv().getOrDefault(
            "CAMUNDA_URL", 
            "http://localhost:8080/engine-rest"  // Fallback für lokale Entwicklung
        );
    
    private static final String CLIENT_DATA_API_URL = 
        System.getenv().getOrDefault(
            "CLIENT_API_URL", 
            "http://localhost:8082"
        );
    
    private static final String TOPIC = 
        System.getenv().getOrDefault(
            "WORKER_TOPIC", 
            "group6_customer_lookup"
        );
}
```

**Starten mit Custom-Werten:**
```powershell
$env:CAMUNDA_URL="http://prod-camunda:8080/engine-rest"
$env:CLIENT_API_URL="http://prod-api:8082"
$env:WORKER_TOPIC="prod_customer_lookup"
java -jar target/customer-lookup-worker-1.0.0.jar
```

#### Option 2: Properties-Datei

**application.properties:**
```properties
camunda.url=http://group6:p5TuHbjEadLeT6L@192.168.111.3:8080/engine-rest
client.api.url=http://localhost:8082
worker.topic=group6_customer_lookup
worker.lock.duration=1000
worker.async.timeout=1000
worker.retry.count=3
worker.retry.timeout=60000
```

**Laden der Properties:**
```java
Properties props = new Properties();
try (InputStream input = CustomerLookupWorker.class
        .getClassLoader()
        .getResourceAsStream("application.properties")) {
    props.load(input);
}

String camundaUrl = props.getProperty("camunda.url");
String clientApiUrl = props.getProperty("client.api.url");
String topic = props.getProperty("worker.topic");
```

#### Option 3: Kommandozeilen-Parameter

```java
public static void main(String[] args) {
    String camundaUrl = args.length > 0 ? args[0] : DEFAULT_CAMUNDA_URL;
    String clientApiUrl = args.length > 1 ? args[1] : DEFAULT_CLIENT_API_URL;
    String topic = args.length > 2 ? args[2] : DEFAULT_TOPIC;
    
    // ...
}
```

**Starten:**
```powershell
java -jar target/customer-lookup-worker-1.0.0.jar `
    "http://camunda:8080/engine-rest" `
    "http://api:8082" `
    "my_topic"
```

### Konfigurations-Matrix für verschiedene Umgebungen

| Umgebung | Camunda URL | Client API URL | Topic | Lock Duration |
|----------|-------------|----------------|-------|---------------|
| **Lokal (Dev)** | http://localhost:8080/engine-rest | http://localhost:8082 | dev_customer_lookup | 1000 ms |
| **Integration** | http://int-camunda:8080/engine-rest | http://int-api:8082 | int_customer_lookup | 2000 ms |
| **Staging** | https://stage-camunda.example.com/engine-rest | https://stage-api.example.com | stage_customer_lookup | 5000 ms |
| **Production** | https://camunda.example.com/engine-rest | https://api.example.com | prod_customer_lookup | 10000 ms |



## 🚀 Verwendung

### Applikation starten

```powershell
# Mit Maven
mvn exec:java -Dexec.mainClass="ch.fhnw.case6.customerlookup.CustomerLookupWorker"

# Mit der generierten JAR
java -jar target/customer-lookup-worker-1.0.0.jar
```

### Erwartete Ausgabe

```
Customer Lookup Worker started.
Topic: group6_customer_lookup
Camunda: http://group6:p5TuHbjEadLeT6L@192.168.111.3:8080/engine-rest
Client Data API: http://localhost:8082
```

### Laufzeit-Verhalten

Der Worker läuft kontinuierlich und:

1. Pollt regelmäßig die Camunda Engine nach neuen Tasks
2. Verarbeitet gefundene Tasks asynchron
3. Locked Tasks für die konfigurierte Dauer (1000 ms)
4. Gibt bei erfolgreicher Verarbeitung die Variablen zurück

## 📁 Projektstruktur

```
customer-lookup-worker/
├── pom.xml
├── README.md
└── src/
    └── main/
        └── java/
            └── ch/
                └── fhnw/
                    └── case6/
                        └── customerlookup/
                            ├── CustomerLookupWorker.java        # Main-Klasse
                            ├── client/
                            │   └── ClientDataApiClient.java     # REST-Client
                            ├── dto/
                            │   └── CustomerData.java            # Daten-Objekt
                            ├── exception/
                            │   └── CustomerDataNotFoundException.java  # Custom Exception
                            ├── handler/
                            │   └── CustomerLookupExternalTaskHandler.java  # Task-Handler
                            └── service/
                                └── CustomerLookupService.java   # Business-Logik
```

## 🔄 Prozessablauf

### Sequenzdiagramm

```
┌─────────┐          ┌──────────┐          ┌─────────┐          ┌──────────────┐
│ Camunda │          │  Handler │          │ Service │          │  API Client  │
└────┬────┘          └────┬─────┘          └────┬────┘          └──────┬───────┘
     │                    │                     │                       │
     │  External Task     │                     │                       │
     │  (customerRef)     │                     │                       │
     ├───────────────────>│                     │                       │
     │                    │                     │                       │
     │                    │  lookupCustomer()   │                       │
     │                    ├────────────────────>│                       │
     │                    │                     │                       │
     │                    │                     │   getCustomerData()   │
     │                    │                     ├──────────────────────>│
     │                    │                     │                       │
     │                    │                     │                       │ HTTP GET
     │                    │                     │                       ├──────────>
     │                    │                     │                       │ /api/customers/{ref}
     │                    │                     │                       │
     │                    │                     │   CustomerData (JSON) │
     │                    │                     │<──────────────────────┤
     │                    │                     │                       │
     │                    │  CustomerData +     │                       │
     │                    │  success=true/false │                       │
     │                    │<────────────────────┤                       │
     │                    │                     │                       │
     │  complete(vars)    │                     │                       │
     │<───────────────────┤                     │                       │
     │                    │                     │                       │
```

### Erfolgreicher Ablauf

```
1. Task empfangen von Camunda
   ↓
2. customerReference aus Task-Variable extrahieren
   ↓
3. REST-Call an Client Data API
   ↓
4. Kundendaten validieren (destination, recepientPhone, email)
   ↓
5. customerLookupSuccess = true/false setzen
   ↓
6. Variablen an Camunda zurückgeben:
   - destination
   - recepientPhone
   - email
   - customerLookupSuccess
   ↓
7. Task complete markieren
```

### Input-Variablen (von Camunda)

| Variable | Typ | Beschreibung |
|----------|-----|--------------|
| `customerReference` | String | Eindeutige Kundenreferenz |

### Output-Variablen (an Camunda)

| Variable | Typ | Beschreibung |
|----------|-----|--------------|
| `destination` | String | Lieferadresse des Kunden |
| `recepientPhone` | String | Telefonnummer des Empfängers |
| `email` | String | E-Mail-Adresse |
| `customerLookupSuccess` | Boolean | `true` wenn alle Daten vollständig, sonst `false` |

## 💻 Code-Beispiele und Erklärungen

### 1. Main-Klasse: Worker-Initialisierung

```java
public class CustomerLookupWorker {
    public static void main(String[] args) {
        // 1️⃣ API Client erstellen - kommuniziert mit der REST-API
        ClientDataApiClient apiClient = new ClientDataApiClient(CLIENT_DATA_API_URL);
        
        // 2️⃣ Service-Layer erstellen - enthält Business-Logik
        CustomerLookupService lookupService = new CustomerLookupService(apiClient);
        
        // 3️⃣ Handler erstellen - verarbeitet Camunda Tasks
        CustomerLookupExternalTaskHandler handler = 
            new CustomerLookupExternalTaskHandler(lookupService);
        
        // 4️⃣ Camunda Client konfigurieren
        ExternalTaskClient client = ExternalTaskClient.create()
                .baseUrl(CAMUNDA_BASE_URL)           // Camunda Engine URL
                .asyncResponseTimeout(1000)          // Timeout für Polling
                .build();
        
        // 5️⃣ Topic abonnieren und starten
        client.subscribe(TOPIC)
                .lockDuration(1000)                  // Task-Lock Dauer
                .handler(handler)                    // Unser Handler
                .open();                             // Polling starten
    }
}
```

**Erklärung:**
- **Dependency Injection:** Komponenten werden von außen nach innen aufgebaut
- **Separation of Concerns:** Jede Schicht hat eine klare Verantwortung
- **Lock Duration:** Task wird für 1 Sekunde gesperrt während Verarbeitung

---

### 2. Task Handler: Hauptverarbeitungslogik

```java
@Override
public void execute(ExternalTask externalTask, ExternalTaskService externalTaskService) {
    // ⬇️ INPUT: Variable aus Camunda-Prozess lesen
    String customerReference = externalTask.getVariable("customerReference");
    
    try {
        // 🔍 Business-Logik: Kundendaten abrufen
        CustomerData customerData = lookupService.lookupCustomerData(customerReference);
        
        // 📦 Ergebnis-Variablen vorbereiten
        Map<String, Object> variables = new HashMap<>();
        variables.put("destination", customerData.getDestination());
        variables.put("recepientPhone", customerData.getRecepientPhone());
        variables.put("email", customerData.getEmail());
        variables.put("customerLookupSuccess", customerData.isCustomerLookupSuccess());
        
        // ✅ SUCCESS: Task als erfolgreich markieren
        externalTaskService.complete(externalTask, variables);
        
    } catch (IllegalArgumentException | CustomerDataNotFoundException ex) {
        // ⚠️ BUSINESS ERROR: Fallback ohne Retry
        completeWithFallback(externalTask, externalTaskService, ex.getMessage());
        
    } catch (ProcessingException | Exception ex) {
        // 🔄 TECHNICAL ERROR: Retry-Logik aktivieren
        handleTechnicalError(externalTask, externalTaskService, ex);
    }
}
```

**Fehlerbehandlungs-Strategie:**

```
┌─────────────────────────────┐
│   Exception aufgetreten?    │
└──────────┬──────────────────┘
           │
           ├─── IllegalArgumentException? ──> ⚠️ Fallback (customerLookupSuccess=false)
           │
           ├─── CustomerDataNotFoundException? ──> ⚠️ Fallback (customerLookupSuccess=false)
           │
           ├─── ProcessingException? ──> 🔄 Retry (max 3x, 60s Pause)
           │
           └─── Andere Exception? ──> 🔄 Retry (max 3x, 60s Pause)
```

---

### 3. Service Layer: Business-Validierung

```java
public CustomerData lookupCustomerData(String customerReference) {
    // 1️⃣ Input-Validierung
    if (customerReference == null || customerReference.trim().isEmpty()) {
        throw new IllegalArgumentException("customerReference is missing");
    }
    
    // 2️⃣ API-Call durchführen
    CustomerData customerData = apiClient.getCustomerData(customerReference);
    
    // 3️⃣ Vollständigkeits-Check
    customerData.setCustomerLookupSuccess(isComplete(customerData));
    
    return customerData;
}

private boolean isComplete(CustomerData data) {
    // ✅ Alle 3 Pflichtfelder müssen gefüllt sein
    return data != null
            && isNotBlank(data.getDestination())       // ✓ Adresse vorhanden?
            && isNotBlank(data.getRecepientPhone())    // ✓ Telefon vorhanden?
            && isNotBlank(data.getEmail());            // ✓ E-Mail vorhanden?
}
```

**Validierungslogik visualisiert:**

```
CustomerData empfangen
         │
         ├─ destination == null/empty? ──> ❌ customerLookupSuccess = false
         │
         ├─ recepientPhone == null/empty? ──> ❌ customerLookupSuccess = false
         │
         ├─ email == null/empty? ──> ❌ customerLookupSuccess = false
         │
         └─ Alle Felder gefüllt ──> ✅ customerLookupSuccess = true
```

---

### 4. API Client: REST-Kommunikation

```java
public CustomerData getCustomerData(String customerReference) {
    Client client = ClientBuilder.newClient();
    
    try {
        // 🌐 HTTP GET Request
        Response response = client
                .target(serviceUrl)
                .path("/api/customers/" + customerReference)  // URL: /api/customers/CUST123
                .request(MediaType.APPLICATION_JSON_TYPE)
                .get();
        
        int status = response.getStatus();
        
        // 📊 HTTP Status Code Handling
        if (status == 200) {
            // ✅ Erfolg: JSON zu CustomerData deserialisieren
            return response.readEntity(CustomerData.class);
        }
        
        if (status == 404) {
            // 🔍 Nicht gefunden: Business Exception
            throw new CustomerDataNotFoundException(customerReference);
        }
        
        if (status >= 400 && status < 500) {
            // ⚠️ Client Error: Business Exception
            throw new IllegalArgumentException("Invalid request. HTTP: " + status);
        }
        
        // 💥 Server Error: Technical Exception (führt zu Retry)
        throw new ProcessingException("Technical error. HTTP: " + status);
        
    } finally {
        client.close();  // 🧹 Ressourcen freigeben
    }
}
```

**HTTP Status Code Mapping:**

```
┌─────────────────┬──────────────────────┬────────────────────┐
│  HTTP Status    │  Exception Type      │  Verhalten         │
├─────────────────┼──────────────────────┼────────────────────┤
│  200 OK         │  -                   │  ✅ Erfolg          │
│  404 Not Found  │  CustomerDataNot...  │  ⚠️ Fallback        │
│  4xx Client     │  IllegalArgument...  │  ⚠️ Fallback        │
│  5xx Server     │  ProcessingException │  🔄 Retry (3x)      │
│  Network Error  │  ProcessingException │  🔄 Retry (3x)      │
└─────────────────┴──────────────────────┴────────────────────┘
```

---

### 5. Retry-Mechanismus erklärt

```java
private void handleTechnicalError(ExternalTask externalTask,
                                   ExternalTaskService externalTaskService,
                                   Exception ex) {
    Integer retries = externalTask.getRetries();
    
    // 🎯 Remaining Retries berechnen
    int remainingRetries;
    if (retries == null) {
        remainingRetries = DEFAULT_RETRIES - 1;  // Erster Fehler: 3-1 = 2 Retries übrig
    } else {
        remainingRetries = retries - 1;           // Weiterer Fehler: retries - 1
    }
    
    if (remainingRetries > 0) {
        // 🔄 Retry schedulen
        externalTaskService.handleFailure(
                externalTask,
                "Technical error while loading customer data",
                ex.getMessage(),
                remainingRetries,           // Anzahl verbleibender Retries
                RETRY_TIMEOUT_MS            // 60.000 ms = 1 Minute Wartezeit
        );
    } else {
        // 🏁 Alle Retries aufgebraucht: Fallback
        Map<String, Object> variables = new HashMap<>();
        variables.put("customerLokupSuccess", false);
        externalTaskService.complete(externalTask, variables);
    }
}
```

**Retry-Ablauf visualisiert:**

```
1. Versuch
   │
   ├─ ❌ Fehler
   │
   ⏱️ Warte 60 Sekunden
   │
2. Versuch (2 Retries übrig)
   │
   ├─ ❌ Fehler
   │
   ⏱️ Warte 60 Sekunden
   │
3. Versuch (1 Retry übrig)
   │
   ├─ ❌ Fehler
   │
   ⏱️ Warte 60 Sekunden
   │
4. Versuch (0 Retries übrig)
   │
   ├─ ❌ Fehler
   │
   └─> 🏁 Fallback: customerLookupSuccess = false
```

---

### 6. Datenfluss durch die Schichten

```
┌─────────────────────────────────────────────────────────────────┐
│                        CAMUNDA ENGINE                           │
│  Variable: customerReference = "CUST-2024-001"                  │
└────────────────────────┬────────────────────────────────────────┘
                         │ External Task
                         ▼
┌─────────────────────────────────────────────────────────────────┐
│              HANDLER LAYER (CustomerLookupExternalTaskHandler)  │
│  • Task entgegennehmen                                          │
│  • Variable extrahieren: "CUST-2024-001"                        │
│  • Service aufrufen                                             │
└────────────────────────┬────────────────────────────────────────┘
                         │ String customerReference
                         ▼
┌─────────────────────────────────────────────────────────────────┐
│              SERVICE LAYER (CustomerLookupService)              │
│  • Input validieren                                             │
│  • API Client aufrufen                                          │
│  • Vollständigkeit prüfen                                       │
└────────────────────────┬────────────────────────────────────────┘
                         │ String customerReference
                         ▼
┌─────────────────────────────────────────────────────────────────┐
│              CLIENT LAYER (ClientDataApiClient)                 │
│  • HTTP GET: /api/customers/CUST-2024-001                       │
│  • Response: { "destination": "...", "email": "...", ... }      │
│  • JSON → CustomerData Object                                   │
└────────────────────────┬────────────────────────────────────────┘
                         │ CustomerData
                         ▼
┌─────────────────────────────────────────────────────────────────┐
│              SERVICE LAYER (CustomerLookupService)              │
│  customerData.setCustomerLookupSuccess(                         │
│    destination ✓ && phone ✓ && email ✓                          │
│  ) → true                                                       │
└────────────────────────┬────────────────────────────────────────┘
                         │ CustomerData (enriched)
                         ▼
┌─────────────────────────────────────────────────────────────────┐
│              HANDLER LAYER                                      │
│  Map<String, Object> variables = {                              │
│    "destination": "Musterstrasse 123, 4000 Basel",              │
│    "recepientPhone": "+41 61 123 45 67",                        │
│    "email": "kunde@example.com",                                │
│    "customerLookupSuccess": true                                │
│  }                                                              │
└────────────────────────┬────────────────────────────────────────┘
                         │ variables Map
                         ▼
┌─────────────────────────────────────────────────────────────────┐
│                        CAMUNDA ENGINE                           │
│  Task completed ✅                                              │
│  Prozess setzt fort mit neuen Variablen                         │
└─────────────────────────────────────────────────────────────────┘
```



## ⚠️ Fehlerbehandlung

Der Worker implementiert eine robuste Fehlerbehandlung mit drei verschiedenen Strategien:

### Fehlerbehandlungs-Übersicht

```
                    ┌─────────────────────┐
                    │  Exception gefangen │
                    └──────────┬──────────┘
                               │
                ┌──────────────┴──────────────┐
                │                             │
        ┌───────▼────────┐          ┌────────▼─────────┐
        │ Business-Fehler │          │ Technische Fehler│
        │                 │          │                  │
        │ • 404 Not Found │          │ • Network Error  │
        │ • Missing Param │          │ • Timeout        │
        │ • 4xx Errors    │          │ • 5xx Errors     │
        └───────┬─────────┘          └────────┬─────────┘
                │                             │
                │                    ┌────────▼─────────┐
                │                    │  Retry verfügbar?│
                │                    └────────┬─────────┘
                │                             │
                │                    ┌────────┴────────┐
                │                    │                 │
                │              ┌─────▼────┐      ┌────▼─────┐
                │              │ JA (1-3) │      │   NEIN   │
                │              └─────┬────┘      └────┬─────┘
                │                    │                │
                │              ⏱️ Wait 60s            │
                │                    │                │
                │              🔄 Retry Task          │
                │                                     │
                └─────────────────────────────────────┘
                                      │
                            ┌─────────▼──────────┐
                            │  Complete Task     │
                            │  success = false   │
                            │  (Fallback-Pfad)   │
                            └────────────────────┘
```

### 1. Business-Fehler (Fallback)

**Auslöser:** 
- `IllegalArgumentException` (z.B. fehlende customerReference)
- `CustomerDataNotFoundException` (HTTP 404)

**Verhalten:**
- Task wird als **complete** markiert
- `customerLookupSuccess = false` wird gesetzt
- Prozess setzt fort (Fallback-Pfad)

### 2. Technische Fehler (Retry)

**Auslöser:**
- `ProcessingException` (Netzwerkfehler, Timeouts)
- Andere unerwartete Exceptions

**Verhalten:**
- Retry mit konfigurierbarer Strategie
- **3 Versuche** (DEFAULT_RETRIES)
- **60 Sekunden** Wartezeit zwischen Versuchen
- Nach letztem Versuch: Fallback mit `customerLookupSuccess = false`

### 3. HTTP-Status-Code-Behandlung

| Status Code | Behandlung | Exception |
|-------------|------------|-----------|
| 200 | Erfolg | - |
| 404 | Business-Fehler | CustomerDataNotFoundException |
| 4xx (andere) | Business-Fehler | IllegalArgumentException |
| 5xx | Technischer Fehler | ProcessingException |

## 🛠️ Entwicklung

### Code Style

Das Projekt folgt Standard-Java-Konventionen:
- Package-Struktur nach Schichten (client, dto, exception, handler, service)
- Dependency Injection über Konstruktor
- Unveränderliche Dependencies (final)
- Aussagekräftige Namen

### Tests erstellen

Um Tests hinzuzufügen, ergänze die `pom.xml` mit JUnit 5:

```xml
<dependency>
    <groupId>org.junit.jupiter</groupId>
    <artifactId>junit-jupiter</artifactId>
    <version>5.9.2</version>
    <scope>test</scope>
</dependency>
```

### Erweiterungen

**Weitere API-Endpoints hinzufügen:**
1. Neue Methoden in `ClientDataApiClient` erstellen
2. Service-Layer erweitern
3. Handler anpassen

**Konfiguration externalisieren:**
```java
// In CustomerLookupWorker.java
private static final String CAMUNDA_BASE_URL = 
    System.getenv().getOrDefault("CAMUNDA_URL", "http://localhost:8080/engine-rest");
```

## 📖 Beispiel-Szenarien

### Szenario 1: Erfolgreicher Lookup mit vollständigen Daten

**Eingabe (Camunda):**
```json
{
  "customerReference": "CUST-2024-001"
}
```

**API Response (Client Data API):**
```json
{
  "customerReference": "CUST-2024-001",
  "destination": "Musterstrasse 123, 4000 Basel",
  "recepientPhone": "+41 61 123 45 67",
  "email": "kunde@example.com",
  "country": "CH"
}
```

**Output (zurück an Camunda):**
```json
{
  "destination": "Musterstrasse 123, 4000 Basel",
  "recepientPhone": "+41 61 123 45 67",
  "email": "kunde@example.com",
  "customerLookupSuccess": true  ← ✅ Alle Pflichtfelder vorhanden
}
```

**Prozess-Verhalten:** ✅ Gateway leitet zum Success-Pfad

---

### Szenario 2: Unvollständige Kundendaten

**Eingabe (Camunda):**
```json
{
  "customerReference": "CUST-2024-002"
}
```

**API Response (Client Data API):**
```json
{
  "customerReference": "CUST-2024-002",
  "destination": "Teststrasse 456, 8000 Zürich",
  "recepientPhone": null,           ← ❌ Telefon fehlt
  "email": "",                      ← ❌ E-Mail leer
  "country": "CH"
}
```

**Output (zurück an Camunda):**
```json
{
  "destination": "Teststrasse 456, 8000 Zürich",
  "recepientPhone": null,
  "email": "",
  "customerLookupSuccess": false  ← ⚠️ Unvollständig
}
```

**Prozess-Verhalten:** ⚠️ Gateway leitet zum Fallback-Pfad (z.B. manuelle Nachbearbeitung)

---

### Szenario 3: Kunde nicht gefunden (404)

**Eingabe (Camunda):**
```json
{
  "customerReference": "INVALID-123"
}
```

**API Response:**
```
HTTP 404 Not Found
```

**Exception:**
```java
CustomerDataNotFoundException: Customer data not found for customerReference: INVALID-123
```

**Output (zurück an Camunda):**
```json
{
  "customerLookupSuccess": false  ← ⚠️ Nicht gefunden
}
```

**Console Log:**
```
Customer lookup fallback. Reason: Customer data not found for customerReference: INVALID-123
```

**Prozess-Verhalten:** ⚠️ Task wird als complete markiert, Fallback-Pfad

---

### Szenario 4: API temporär nicht erreichbar (Retry)

**Eingabe (Camunda):**
```json
{
  "customerReference": "CUST-2024-003"
}
```

**API Response:**
```
HTTP 503 Service Unavailable
```

**Verhalten:**

```
🔄 Versuch 1 ───❌──> ProcessingException
                      ⏱️ Warte 60 Sekunden
                      
🔄 Versuch 2 ───❌──> ProcessingException
                      ⏱️ Warte 60 Sekunden
                      
🔄 Versuch 3 ───✅──> HTTP 200 OK (Service wieder online)
                      ✅ Task erfolgreich
```

**Console Log:**
```
Technical error. Retry scheduled. Remaining retries: 2
Technical error. Retry scheduled. Remaining retries: 1
Customer lookup completed. customerReference=CUST-2024-003, success=true
```

---

### Szenario 5: Persistenter API-Fehler (alle Retries aufgebraucht)

**Eingabe (Camunda):**
```json
{
  "customerReference": "CUST-2024-004"
}
```

**API Response (alle 4 Versuche):**
```
Network timeout after 30 seconds
```

**Verhalten:**

```
🔄 Versuch 1 ───❌──> ProcessingException (Retries left: 2)
                      ⏱️ Warte 60 Sekunden
                      
🔄 Versuch 2 ───❌──> ProcessingException (Retries left: 1)
                      ⏱️ Warte 60 Sekunden
                      
🔄 Versuch 3 ───❌──> ProcessingException (Retries left: 0)
                      ⏱️ Warte 60 Sekunden
                      
🔄 Versuch 4 ───❌──> 🏁 Fallback aktiviert
```

**Output (zurück an Camunda):**
```json
{
  "customerLookupSuccess": false  ← 🏁 Nach allen Retries
}
```

**Console Log:**
```
Technical error. Retry scheduled. Remaining retries: 2
Technical error. Retry scheduled. Remaining retries: 1
Technical error. Retry scheduled. Remaining retries: 0
Technical error after retries. Process continues with fallback.
```

**Prozess-Verhalten:** 🏁 Fallback-Pfad (z.B. Alert an Support-Team)

---

### Szenario 6: Fehlende customerReference

**Eingabe (Camunda):**
```json
{
  "customerReference": null
}
```
oder
```json
{
  "customerReference": "   "
}
```

**Exception:**
```java
IllegalArgumentException: customerReference is missing
```

**Output (zurück an Camunda):**
```json
{
  "customerLookupSuccess": false  ← ⚠️ Validierung fehlgeschlagen
}
```

**Console Log:**
```
Customer lookup fallback. Reason: customerReference is missing
```

**Prozess-Verhalten:** ⚠️ Sofortiger Fallback ohne Retry (kein Netzwerkfehler)



## 📚 Abhängigkeiten

### Dependency Tree

```
customer-lookup-worker (1.0.0)
│
├─── Camunda Integration
│    └─── camunda-external-task-client (1.3.1)
│         ├─── Camunda Client Core
│         ├─── Camunda Commons
│         └─── HTTP Client Libs
│
├─── REST Client (JAX-RS)
│    ├─── javax.ws.rs-api (2.1.1)              [API Standard]
│    │
│    └─── Jersey Implementation (2.35)
│         ├─── jersey-client                   [REST Client Core]
│         ├─── jersey-hk2                      [Dependency Injection]
│         └─── jersey-media-json-jackson       [JSON Serialisierung]
│              ├─── Jackson Core
│              ├─── Jackson Databind
│              └─── Jackson Annotations
│
├─── XML Binding (für Java 11+)
│    ├─── jaxb-api (2.3.1)                     [API]
│    └─── jaxb-runtime (2.3.3)                 [Implementation]
│         └─── JAXB Core
│
└─── Logging
     └─── slf4j-simple (1.7.36)
          └─── slf4j-api
```

### Haupt-Abhängigkeiten

| Abhängigkeit | Version | Zweck |
|--------------|---------|-------|
| **camunda-external-task-client** | 1.3.1 | Camunda External Task Integration |
| **jersey-client** | 2.35 | JAX-RS REST-Client |
| **jersey-media-json-jackson** | 2.35 | JSON-Serialisierung/Deserialisierung |
| **javax.ws.rs-api** | 2.1.1 | JAX-RS API Standard |
| **jaxb-api** | 2.3.1 | XML Binding (für Java 11+) |
| **slf4j-simple** | 1.7.36 | Logging |

### Dependency-Verwendung im Code

#### 1. Camunda External Task Client

**Zweck:** Integration mit Camunda BPM Engine als External Task Worker

**Verwendete Klassen:**
```java
import org.camunda.bpm.client.ExternalTaskClient;        // Client erstellen
import org.camunda.bpm.client.task.ExternalTask;         // Task-Objekt
import org.camunda.bpm.client.task.ExternalTaskHandler;  // Handler-Interface
import org.camunda.bpm.client.task.ExternalTaskService;  // Task-Operationen
```

**Code-Beispiel:**
```java
// Client erstellen und Topic abonnieren
ExternalTaskClient client = ExternalTaskClient.create()
        .baseUrl("http://camunda:8080/engine-rest")
        .build();

client.subscribe("my_topic")
        .handler((externalTask, externalTaskService) -> {
            // Task verarbeiten
            String var = externalTask.getVariable("myVar");
            externalTaskService.complete(externalTask);
        })
        .open();
```

---

#### 2. JAX-RS / Jersey (REST Client)

**Zweck:** HTTP-Kommunikation mit der Client Data API

**Verwendete Klassen:**
```java
import javax.ws.rs.client.Client;                // REST Client
import javax.ws.rs.client.ClientBuilder;         // Client Factory
import javax.ws.rs.core.MediaType;               // Content-Type
import javax.ws.rs.core.Response;                // HTTP Response
import javax.ws.rs.ProcessingException;          // Network Errors
```

**Code-Beispiel:**
```java
Client client = ClientBuilder.newClient();

Response response = client
        .target("http://api:8082")               // Base URL
        .path("/api/customers/CUST123")          // Path
        .request(MediaType.APPLICATION_JSON)     // Accept Header
        .get();                                  // HTTP GET

CustomerData data = response.readEntity(CustomerData.class);  // JSON → Object
```

**Was passiert unter der Haube:**
```
1. Jersey Client sendet HTTP Request:
   GET http://api:8082/api/customers/CUST123
   Accept: application/json

2. Server antwortet mit JSON:
   {
     "customerReference": "CUST123",
     "destination": "Musterstrasse 123",
     ...
   }

3. Jackson deserialisiert JSON → CustomerData Object:
   CustomerData {
     customerReference = "CUST123"
     destination = "Musterstrasse 123"
     ...
   }
```

---

#### 3. Jackson (JSON Processing)

**Zweck:** Automatische Serialisierung/Deserialisierung von JSON

**Implizite Verwendung:**
```java
// JSON String → Java Object (Deserialisierung)
CustomerData data = response.readEntity(CustomerData.class);

// Java Object → JSON String (Serialisierung)
// (wird von Jersey automatisch gemacht)
```

**Wie Jackson die DTO-Klasse nutzt:**
```java
public class CustomerData {
    private String customerReference;  // ← Wird zu "customerReference" in JSON
    private String destination;        // ← Wird zu "destination" in JSON
    
    // Getter/Setter werden von Jackson verwendet:
    public String getCustomerReference() { return customerReference; }
    public void setCustomerReference(String ref) { this.customerReference = ref; }
}
```

**JSON Mapping:**
```json
{
  "customerReference": "CUST123",           ← setCustomerReference("CUST123")
  "destination": "Musterstrasse 123",       ← setDestination("Musterstrasse 123")
  "recepientPhone": "+41 61 123 45 67",     ← setRecepientPhone("+41 61 123 45 67")
  "email": "kunde@example.com",             ← setEmail("kunde@example.com")
  "country": "CH"                           ← setCountry("CH")
}
```

---

#### 4. JAXB (XML Binding)

**Zweck:** Benötigt ab Java 11, da JAXB nicht mehr im JDK enthalten ist

**Warum notwendig?**
- Java 8: JAXB war Teil vom JDK
- Java 11+: JAXB wurde entfernt, muss manuell hinzugefügt werden
- Camunda Client nutzt intern JAXB für XML-Verarbeitung

**Ohne JAXB bei Java 11:**
```
java.lang.NoClassDefFoundError: javax/xml/bind/JAXBException
```

**Mit JAXB Dependencies:**
```xml
<dependency>
    <groupId>javax.xml.bind</groupId>
    <artifactId>jaxb-api</artifactId>          <!-- API -->
    <version>2.3.1</version>
</dependency>

<dependency>
    <groupId>org.glassfish.jaxb</groupId>
    <artifactId>jaxb-runtime</artifactId>      <!-- Implementation -->
    <version>2.3.3</version>
</dependency>
```

---

#### 5. SLF4J (Logging)

**Zweck:** Einfaches Logging auf der Konsole

**Verwendete Klassen:**
```java
// Implizit durch System.out.println() und Camunda/Jersey Logs
```

**Log-Output Beispiel:**
```
[main] INFO org.camunda.bpm.client - External Task Client connected
Customer Lookup Worker started.
Topic: group6_customer_lookup
Camunda: http://group6:p5TuHbjEadLeT6L@192.168.111.3:8080/engine-rest
Client Data API: http://localhost:8082
Customer lookup completed. customerReference=CUST-001, success=true
```

**Alternative: Produktives Logging**

Für Production würde man `slf4j-simple` ersetzen durch `logback`:
```xml
<dependency>
    <groupId>ch.qos.logback</groupId>
    <artifactId>logback-classic</artifactId>
    <version>1.2.11</version>
</dependency>
```

Mit `logback.xml` Konfiguration:
```xml
<configuration>
    <appender name="FILE" class="ch.qos.logback.core.FileAppender">
        <file>customer-lookup-worker.log</file>
        <encoder>
            <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>
    
    <root level="INFO">
        <appender-ref ref="FILE" />
    </root>
</configuration>
```

### Dependency Conflicts vermeiden

**Warum Jersey 2.x statt 3.x?**

```
javax.ws.rs-api (2.1.1)  ←  Verwendet javax.* Packages
    ↓
Jersey 2.x               ←  Kompatibel mit javax.ws.rs-api
    ↓
Camunda Client (1.3.1)   ←  Funktioniert mit javax.*


❌ NICHT verwenden:
jakarta.ws.rs-api        ←  Verwendet jakarta.* Packages (neu)
    ↓
Jersey 3.x               ←  Verwendet jakarta.*, INKOMPATIBEL
```

**Regel:** Camunda External Task Client 1.3.1 funktioniert nur mit `javax.ws.rs` (nicht `jakarta.ws.rs`)



### Build-Konfiguration

- **Java Version:** 11
- **Maven Compiler:** 11
- **Encoding:** UTF-8

## 📝 Lizenz

[Hier Lizenzinformationen einfügen]

## 👥 Autoren

- Group 6 - FHNW Case Study

## 🤝 Beitragen

[Hier Contribution Guidelines einfügen]

## 📞 Support

Bei Fragen oder Problemen:
- Issue erstellen im Repository
- Entwicklungsteam kontaktieren

## ❓ FAQ (Häufig gestellte Fragen)

### Allgemein

<details>
<summary><strong>Q: Wie funktioniert der External Task Worker?</strong></summary>

**A:** Der Worker pollt regelmäßig die Camunda Engine nach neuen Tasks:

```
Worker                           Camunda Engine
  │                                    │
  ├────── Fetch & Lock ───────────────>│
  │       "Gibt es Tasks für          │
  │        topic 'group6_customer_    │
  │        lookup'?"                   │
  │                                    │
  │<────── Response ────────────────────┤
  │       "Ja, hier ist Task XYZ"     │
  │       (Task wird gelockt)         │
  │                                    │
  ├─ Task verarbeiten                 │
  │  (API Call, Validierung)          │
  │                                    │
  ├────── Complete ───────────────────>│
  │       "Task XYZ ist fertig,       │
  │        hier sind die Variablen"   │
  │                                    │
  ├────── Fetch & Lock ───────────────>│
  │       "Nächster Task?"            │
  │                                    │
  └──────────── Loop ──────────────────┘
```
</details>

<details>
<summary><strong>Q: Was passiert, wenn der Worker abstürzt während Task-Verarbeitung?</strong></summary>

**A:** Die Lock Duration schützt davor:

```
T0: Worker holt Task und lockt ihn (lockDuration: 1000ms)
T0.5: Worker stürzt ab 💥
T1: Lock läuft ab (nach 1000ms)
T1: Task wird wieder für andere Worker verfügbar
T2: Anderer Worker (oder neu gestarteter Worker) holt den Task
```

**Best Practice:** Lock Duration sollte länger sein als die typische Verarbeitungszeit.
</details>

<details>
<summary><strong>Q: Können mehrere Worker-Instanzen parallel laufen?</strong></summary>

**A:** Ja! Das ist der Hauptvorteil von External Task Workers:

```
┌─────────┐
│ Camunda │
└────┬────┘
     │
     ├────> Worker Instance 1 (Server A)
     │      ├─ Verarbeitet Task 1
     │      └─ Verarbeitet Task 4
     │
     ├────> Worker Instance 2 (Server B)
     │      ├─ Verarbeitet Task 2
     │      └─ Verarbeitet Task 5
     │
     └────> Worker Instance 3 (Server C)
            ├─ Verarbeitet Task 3
            └─ Verarbeitet Task 6
```

Camunda sorgt automatisch für die Verteilung (Load Balancing).
</details>

<details>
<summary><strong>Q: Warum wird `customerLookupSuccess` auf `false` gesetzt statt Exception zu werfen?</strong></summary>

**A:** Business-Fehler sind erwartete Zustände, keine Exceptions:

```
✅ RICHTIG (aktuelle Implementierung):
• Kunde nicht gefunden → customerLookupSuccess = false
• Prozess wählt alternativen Pfad (z.B. manuelle Nachbearbeitung)
• Task wird als "complete" markiert

❌ FALSCH (würde zu Problemen führen):
• Kunde nicht gefunden → Exception werfen
• Prozess würde blockieren
• Task müsste wiederholt werden (sinnlos, da Kunde trotzdem nicht existiert)
```

**Regel:** 
- Technische Fehler (Network, Timeout) → Retry
- Business-Fehler (Not Found, Invalid) → Fallback mit `success=false`
</details>

### Technisch

<details>
<summary><strong>Q: Warum Jersey 2.x statt 3.x?</strong></summary>

**A:** Kompatibilität mit Camunda External Task Client:

```
Camunda Client 1.3.1 → Verwendet javax.ws.rs.*
                       (alte Package-Struktur)

Jersey 2.x → Implementiert javax.ws.rs.* ✅ Kompatibel

Jersey 3.x → Implementiert jakarta.ws.rs.* ❌ Inkompatibel
             (neue Package-Struktur seit Jakarta EE 9)
```

Für Jersey 3.x müsste man auf Camunda Client 7.18+ upgraden.
</details>

<details>
<summary><strong>Q: Warum JAXB Dependencies bei Java 11+?</strong></summary>

**A:** JAXB wurde aus dem JDK entfernt:

```
Java 8:  JDK enthält JAXB ✅
Java 11: JDK enthält JAXB nicht mehr ❌

Lösung: Manuell hinzufügen:
• jaxb-api (Interface)
• jaxb-runtime (Implementation)
```

Ohne diese Dependencies:
```
Exception: java.lang.NoClassDefFoundError: javax/xml/bind/JAXBException
```
</details>

<details>
<summary><strong>Q: Wie kann ich die API-Calls debuggen?</strong></summary>

**A:** Mehrere Optionen:

**1. Logging aktivieren:**
```java
// In ClientDataApiClient
Client client = ClientBuilder.newClient();
client.register(new LoggingFeature(Logger.getLogger("HTTP"), 
                                    Level.INFO, 
                                    LoggingFeature.Verbosity.PAYLOAD_ANY, 
                                    8192));
```

**2. Network-Traffic mit Wireshark/Fiddler:**
```
GET /api/customers/CUST123 HTTP/1.1
Host: localhost:8082
Accept: application/json
```

**3. Mock-Server für Tests:**
```java
// WireMock nutzen
@Test
public void testApiCall() {
    stubFor(get(urlEqualTo("/api/customers/TEST"))
        .willReturn(aResponse()
            .withStatus(200)
            .withBody("{\"customerReference\":\"TEST\",...}")));
    
    CustomerData data = client.getCustomerData("TEST");
    assertNotNull(data);
}
```
</details>

## 🔧 Troubleshooting

### Problem 1: Worker startet nicht

**Symptom:**
```
Exception in thread "main" java.net.ConnectException: Connection refused
```

**Ursachen & Lösungen:**

```
1️⃣ Camunda Engine nicht erreichbar
   ├─ Prüfen: Ist Camunda gestartet?
   ├─ Prüfen: Ist die URL korrekt?
   │   → http://192.168.111.3:8080/engine-rest
   └─ Testen: curl http://192.168.111.3:8080/engine-rest/version

2️⃣ Client Data API nicht erreichbar
   ├─ Prüfen: Ist die API gestartet?
   ├─ Prüfen: Läuft sie auf Port 8082?
   └─ Testen: curl http://localhost:8082/api/customers/TEST

3️⃣ Firewall blockiert
   └─ Windows Firewall: Port 8080 und 8082 freigeben
```

---

### Problem 2: Tasks werden nicht abgeholt

**Symptom:**
```
Customer Lookup Worker started.
[Keine weiteren Log-Einträge]
```

**Debug-Schritte:**

```
1️⃣ Topic-Name prüfen
   BPMN Prozess:          Worker Code:
   ┌──────────────────┐   ┌──────────────────────┐
   │ Topic:           │   │ TOPIC =              │
   │ "group6_customer_│ = │ "group6_customer_    │
   │  lookup"         │   │  lookup"             │
   └──────────────────┘   └──────────────────────┘
   
   ⚠️ Müssen EXAKT übereinstimmen (case-sensitive!)

2️⃣ Process Instance existiert?
   → In Camunda Cockpit prüfen
   → Gibt es wartende External Tasks?

3️⃣ Task bereits gelockt?
   → In Camunda: External Tasks → Locked Tasks
   → Falls ja: Warten bis Lock abläuft

4️⃣ Worker-Credentials korrekt?
   → Test: curl -u group6:p5TuHbjEadLeT6L http://192.168.111.3:8080/engine-rest/version
```

---

### Problem 3: JSON Deserialisierung schlägt fehl

**Symptom:**
```
javax.ws.rs.ProcessingException: Unable to find a MessageBodyReader
```

**Ursache:** Jackson JSON Provider fehlt

**Lösung:**
```xml
<!-- In pom.xml sicherstellen: -->
<dependency>
    <groupId>org.glassfish.jersey.media</groupId>
    <artifactId>jersey-media-json-jackson</artifactId>
    <version>2.35</version>
</dependency>
```

---

### Problem 4: Retry funktioniert nicht

**Symptom:**
```
Task wird sofort als complete markiert statt Retry
```

**Ursache:** Exception-Typ stimmt nicht

**Code prüfen:**
```java
try {
    // ...
} catch (IllegalArgumentException ex) {
    // ⚠️ Dies führt zu Fallback, NICHT Retry!
    completeWithFallback(...);
    
} catch (ProcessingException ex) {
    // ✅ Dies führt zu Retry
    handleTechnicalError(...);
}
```

**Mapping:**
```
HTTP 404      → CustomerDataNotFoundException → Fallback ⚠️
HTTP 500      → ProcessingException → Retry 🔄
Network Error → ProcessingException → Retry 🔄
```

---

### Problem 5: Lock Duration zu kurz

**Symptom:**
```
Task wird während Verarbeitung von anderem Worker übernommen
```

**Ursache:** Verarbeitung dauert länger als Lock Duration

**Visualisierung:**
```
T0:     Worker A lockt Task (lockDuration: 1000ms)
T0-T1:  Worker A verarbeitet (dauert 2000ms!)
T1:     ❌ Lock läuft ab
T1:     Worker B sieht Task als verfügbar
T1:     Worker B lockt Task
T1-T2:  Worker A und B verarbeiten parallel! 💥 Duplikat!
```

**Lösung:**
```java
client.subscribe(TOPIC)
        .lockDuration(5000)  // ← Erhöhen auf 5 Sekunden
        .handler(handler)
        .open();
```

**Faustregel:** lockDuration = 2 × durchschnittliche Verarbeitungszeit

---

### Problem 6: Zu viele Retries

**Symptom:**
```
Task wird 10x wiederholt, sollte aber nur 3x sein
```

**Ursache:** Retry-Count wird nicht korrekt dekrementiert

**Code prüfen:**
```java
Integer retries = externalTask.getRetries();

// ✅ RICHTIG:
int remaining = (retries == null) ? DEFAULT_RETRIES - 1 : retries - 1;

// ❌ FALSCH:
int remaining = DEFAULT_RETRIES;  // Wird nie weniger!
```

---

### Problem 7: Performance-Probleme

**Symptom:**
```
Worker verarbeitet nur 1-2 Tasks pro Sekunde
```

**Optimierungen:**

```java
// 1. Parallele Verarbeitung aktivieren
client.subscribe(TOPIC)
        .lockDuration(5000)
        .handler(handler)
        .open();

// 2. Mehrere Worker-Threads
ExternalTaskClient client = ExternalTaskClient.create()
        .baseUrl(CAMUNDA_BASE_URL)
        .maxTasks(10)  // ← 10 Tasks parallel abrufen
        .build();

// 3. Connection Pooling für HTTP
ClientConfig config = new ClientConfig();
config.property(ClientProperties.CONNECT_TIMEOUT, 5000);
config.property(ClientProperties.READ_TIMEOUT, 5000);
config.connectorProvider(new ApacheConnectorProvider());  // Pool included

Client client = ClientBuilder.newClient(config);
```

**Monitoring:**
```java
// Verarbeitungszeit messen
long start = System.currentTimeMillis();
// ... Verarbeitung ...
long duration = System.currentTimeMillis() - start;
System.out.println("Processing took: " + duration + "ms");
```

---

### Debug-Checklist

Wenn etwas nicht funktioniert:

- [ ] Camunda Engine erreichbar? (`curl http://camunda:8080/engine-rest/version`)
- [ ] Client Data API erreichbar? (`curl http://localhost:8082/api/customers/TEST`)
- [ ] Topic-Name korrekt? (BPMN ↔ Worker Code)
- [ ] Process Instance gestartet? (Camunda Cockpit prüfen)
- [ ] Credentials korrekt? (Basic Auth)
- [ ] Java 11+ installiert? (`java -version`)
- [ ] Dependencies vollständig? (`mvn dependency:tree`)
- [ ] Lock Duration ausreichend? (>= 2× Verarbeitungszeit)
- [ ] Logs prüfen? (Console Output)
- [ ] Port-Konflikte? (`netstat -an | findstr :8080`)



---

**Version:** 1.0.0  
**Letzte Aktualisierung:** 2026-05-18

