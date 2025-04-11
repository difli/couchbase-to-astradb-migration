# Couchbase Java Backend Migration to Astra DB

This project documents the process of migrating the backend service of the original "Try Couchbase" sample application from using Couchbase Server to using DataStax Astra DB, specifically leveraging the JSON Data API and its Vector Search capabilities.

A key goal of this migration was to **keep the existing frontend application (`try-cb-frontend-v2`) completely unchanged**. This required the migrated backend (`try-cb-java-astra`) to maintain the exact same REST API contract (paths, request/response structures) as the original Couchbase-based backend (`try-cb-java`).

## Original Projects

*   **Original Frontend:** [try-cb-frontend-v2](https://github.com/couchbaselabs/try-cb-frontend-v2) 
*   **Original Backend:** [try-cb-java](https://github.com/couchbaselabs/try-cb-java) 

## Project Structure (This Repository)

*   `try-cb-java-astra/`: The migrated Spring Boot backend application using Astra DB Data API and `astra-db-java` SDK.
*   `migrate_couchbase_to_astra_with_vector.py`: Python script to migrate data from a Couchbase `travel-sample` bucket to Astra DB, enabling Vectorize for the `hotel` collection.
*   `requirements.txt`: Python dependencies for the migration script.
*   `.env` (template): File to store Astra DB credentials for the migration script.
*   `(This README.md)`

## Migration Strategy Overview

The migration involved two main phases:

1.  **Data Migration:** Transferring data from the Couchbase `travel-sample` bucket to corresponding collections in Astra DB. This included setting up a vector-enabled collection for hotels to replace the original Full-Text Search (FTS) functionality.
2.  **Backend Refactoring:** Modifying the `try-cb-java` Spring Boot application to remove Couchbase SDK dependencies and logic, replacing them with the Astra DB Data API Java SDK (`astra-db-java`). This required careful adaptation to maintain the existing API contract and work around Data API differences.

## Data Migration (`migrate_couchbase_to_astra_with_vector.py`)

This Python script handles the data transfer process.

**Technology:**

*   Python 3
*   `couchbase` SDK: To connect to and query the source Couchbase bucket.
*   `astrapy` SDK: To interact with the Astra DB JSON Data API (`DataAPIClient`).
*   `python-dotenv`: To load Astra DB credentials from a `.env` file.

**Process:**

1.  **Configuration:** Reads Couchbase and Astra DB connection details from environment variables (loaded via `.env`).
2.  **Connection:** Connects to both Couchbase and Astra DB.
3.  **Collection Handling (`ensure_astra_collection`):**
    *   For each document type (`airline`, `airport`, `route`, `landmark`, `hotel`, `user`, `booking`), the script first attempts to **delete** the corresponding collection in Astra DB.
    *   It then attempts to **create** the collection.
    *   **Vector Collection Creation (Hotels):** For the `hotel` collection, it attempts programmatic creation using `astrapy`'s `CollectionDefinition.builder().set_vector_service(...)`. This configures Astra DB's **Vectorize** feature:
        *   `provider`: "huggingface"
        *   `model_name`: "sentence-transformers/all-MiniLM-L6-v2" (resulting in 384-dimension vectors).
        *   `metric`: Cosine similarity.
    *   **Standard Collections:** For other types, it uses a simple `create_collection` call.
4.  **Data Fetching:** Queries Couchbase to get document IDs for each specified `doc_type`.
5.  **Document Preparation (`get_document_content`):**
    *   Fetches each document from Couchbase by ID.
    *   Prepares a corresponding document for Astra DB.
    *   **Vectorize Trigger:** For `hotel` documents, it concatenates the text content from relevant fields (`name`, `description`, `address`, `city`, `state`, `country`) into a single string. It then adds a special field `"$vectorize"` to the document, setting its value to this combined text string.
6.  **Data Insertion (`insert_batch_astra`):**
    *   Inserts documents into the corresponding Astra collection in batches using `astra_collection.insert_many(documents)`.
    *   When Astra DB receives a document with the `"$vectorize"` field for the `hotel` collection, it automatically uses the configured service (Hugging Face model) to generate the vector embedding from the text and stores it in the default `"$vector"` field.

**Running the Script:**

1.  Ensure Python 3 and `pip` are installed.
2.  Create a virtual environment (optional but recommended): `python -m venv venv_migration` and activate it.
3.  Install dependencies: `pip install -r requirements.txt`.
4.  Create a `.env` file in the project root and populate it with your Astra DB credentials (API Endpoint, Application Token, Keyspace). See the `.env` file in this repository for the required variable names. Also configure Couchbase connection details within the script if needed.
5.  Run the script: `python migrate_couchbase_to_astra_with_vector.py`.

## Running the Full Application Stack

To run the complete application (Migrated Backend + Original Frontend) for testing or development, you need:

1.  **Astra DB Instance:** An Astra DB serverless database instance (a free tier is available).
2.  **Couchbase Server (for initial data source):** Although the migrated backend uses Astra DB, the Python migration script reads data *from* Couchbase. You need a running Couchbase instance with the `travel-sample` bucket loaded for the *initial* data migration.
3.  **Migrated Backend (`try-cb-java-astra`):** This repository.
4.  **Original Frontend (`try-cb-frontend-v2`):** Cloned from its repository.

**Steps:**

### 1. Start Couchbase Server (using Docker)

The easiest way to run Couchbase locally for the data migration step is using Docker.

```bash
# Run this command in your terminal
docker run -d \
  --name couchbase \
  -p 8091-8096:8091-8096 \
  -p 11210:11210 \
  couchbase:community 
```

*   This downloads the Couchbase Community Edition image (if you don't have it) and starts a container named `couchbase`.
*   It maps the necessary ports for the Web UI (8091) and SDK connections.

**Initial Couchbase Setup & Data Loading:**

*   Open your web browser to `http://localhost:8091`.
*   Follow the Couchbase setup wizard:
    *   Choose "Setup New Cluster".
    *   Set a cluster name (e.g., `devcluster`).
    *   Create an Administrator user (e.g., Username: `Administrator`, Password: `password` - **remember these for the migration script configuration**).
    *   Accept default settings for services and memory.
*   Once the cluster is ready, navigate to the "Settings" tab -> "Sample Buckets".
*   Select the `travel-sample` bucket and click "Load Sample Data". This will load the necessary dataset into your local Couchbase instance.

### 2. Prepare Astra DB

*   Create an Astra DB instance if you haven't already.
*   Obtain your **Database ID**, **Region**, **Application Token**, and **Keyspace Name**.
*   **(Optional but Recommended if Script Fails):** Manually create the `hotel` collection:
    *   Go to your Keyspace in the Astra DB UI.
    *   Create a new collection named `hotel`.
    *   Enable "Vector Search".
    *   Set Dimension to `384`.
    *   Set Metric to `cosine`.
    *   Under "Vectorize Settings", enable "Use a Vectorize service", choose Provider `Hugging Face`, and select the Model `sentence-transformers/all-MiniLM-L6-v2`.
    *   Create the other collections (`airline`, `airport`, `route`, `landmark`, `user`, `booking`) as standard collections (no vector settings needed) if the script fails to create them.

### 3. Run the Data Migration Script

*   Follow the steps outlined in the "Data Migration (`migrate_couchbase_to_astra_with_vector.py`) -> Running the Script" section above.
*   Ensure the `.env` file has your correct Astra DB credentials.
*   Ensure the script's Couchbase connection details (`CB_USERNAME`, `CB_PASSWORD`) match the administrator user you created for your Docker instance.
*   Run `python migrate_couchbase_to_astra_with_vector.py`.
*   This will delete/create collections in Astra and populate them with data from your local Couchbase `travel-sample` bucket, using `$vectorize` for hotels.

### 4. Run the Migrated Backend (`try-cb-java-astra`)

*   Follow the steps outlined in the "Running the Migrated Application (`try-cb-java-astra`)" section above.
*   Ensure `application.properties` has your correct Astra DB credentials.
*   Run `mvn spring-boot:run` in the `try-cb-java-astra` directory.
    *   The backend server will start, typically on `http://localhost:8080`.

### 5. Run the Original Frontend (`try-cb-frontend-v2`)

*   Clone the `try-cb-frontend-v2` repository: `git clone https://github.com/couchbase/try-cb-frontend-v2.git` (verify URL)
*   Navigate into the `try-cb-frontend-v2` directory.
*   Install dependencies: `npm install`
*   Start the frontend development server: `npm start`
    *   This usually opens the application automatically in your browser, typically at `http://localhost:3000`.
*   The frontend is typically configured to connect to a backend running on `http://localhost:8080`. If your backend runs on a different port, you might need to adjust the frontend configuration (often via environment variables or a config file - check the frontend repo's README).

Now, you should be able to interact with the application in your browser. Hotel searches will use the Astra DB vector search powered by the migrated backend.

## Running the Migrated Application (`try-cb-java-astra`)

1.  **Prerequisites:**
    *   Java (version compatible with the project, likely 11+).
    *   Maven.
    *   An Astra DB instance with the `travel-sample` data migrated using the Python script (including the vector `hotel` collection).
    *   The original `try-cb-frontend-v2` running and configured to point to the backend (usually `http://localhost:8080`).
2.  **Configuration:** Update `try-cb-java-astra/src/main/resources/application.properties` with your Astra DB Application Token, API Endpoint, and Keyspace.
3.  **Build:** Navigate to the `try-cb-java-astra` directory and run `mvn clean package`.
4.  **Run:** Execute `mvn spring-boot:run` or run the generated JAR file.
5.  Access the frontend in your browser. Searches for hotels should now use vector similarity.

## Conclusion

This project demonstrates a successful migration from a Couchbase backend to Astra DB, incorporating vector search capabilities while maintaining compatibility with an existing frontend. It highlights the importance of understanding SDK specifics (especially previews), adapting to API differences, and carefully managing data migration when incorporating features like vector embeddings. 