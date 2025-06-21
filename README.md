# 250Game 🃏

A personal implementation of the classic Indian card game "250" (Do-Sau-Pachaas) built using Java. This project models the multiplayer logic and gameplay mechanics of the trick-taking game often played with 5 players in many Indian households.

---

## 📌 Overview

This is a **Java-based card game project** developed for learning purposes. The goal is to simulate the gameplay of 250 with proper card dealing, team management, bidding, and trick-taking mechanisms.

- Developed using **Java** and **Spring Boot** (for the server-side)
- GUI made with **Swing** for client-side gameplay
- Supports multiplayer game flow
- Logic based on real-life card rules of 250

---

## 🎮 Game Rules (Brief)

- 5 players split into 2 temporary teams: **callers vs defenders**
- Bidding round to determine trump and game leader
- Team of caller must win **250 points** to succeed
- Trick-taking format similar to Rummy/Bridge

> Detailed rules may vary regionally. This is based on the version familiar to the developer.

---

## 🛠️ Tech Stack

| Layer      | Technology      |
|------------|-----------------|
| Server     | Java, Spring Boot |
| Client GUI | Java Swing       |
| Protocol   | Sockets / REST (WIP) |

---

## 🚀 Getting Started

### Prerequisites
- Java 17+
- Maven or Gradle
- IntelliJ / Eclipse (optional but recommended)

### Clone the Repository
```bash
git clone https://github.com/Amishkakru/250Game.git
cd 250Game
````

### To Run Server

```bash
cd server
./mvnw spring-boot:run
```

### To Run Client

```bash
cd client
# Run the main class with GUI
java -cp target/client.jar com.example.ClientMain
```

---

## 📷 Screenshots

*(Add screenshots here if available)*

---

## 🧱 Project Structure (WIP)

```
250Game/
├── client/       # Java Swing GUI code
├── server/       # Spring Boot server logic
├── shared/       # Common models (cards, players, enums)
```

---

## 🧠 Learnings & Goals

This is a personal passion project to:

* Learn client-server communication in Java
* Practice multiplayer game logic
* Build a Swing-based UI
* Understand state synchronization and event flow

---

## 🧳 Future Plans

* Full multiplayer networking with rooms
* AI bot players for offline mode
* Enhanced UI with animations
* Deployment-ready backend

---

## 🙋‍♂️ Author

**Amish Kakru**

* GitHub: [@Amishkakru](https://github.com/Amishkakru)

---

## 📝 License

This project is open source and free to use under the MIT License.
