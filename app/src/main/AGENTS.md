# ClassroomManager (班级小管家 Third-Party Client) - AI Agent Guide

## 1. Project Overview
This is a native Android application built with Kotlin and Jetpack Compose. It serves as a third-party client for "班级小管家" (welife001.com), allowing users (parents/students) to log in, view class information, check homework/tasks, and review exam scores.

## 2. Tech Stack
*   **UI Toolkit:** Jetpack Compose (Material 3)
*   **Navigation:** Navigation Compose (`NavHostController`)
*   **Networking:** OkHttp3 (Direct API calls and Web Scraping/Cookie Management)
*   **Concurrency:** Kotlin Coroutines (`Dispatchers.IO`)
*   **Image Loading:** Coil (`AsyncImage`)
*   **HTML Parsing:** Jsoup (used during the web login flow)

## 3. Architecture & Data Flow
The project follows a lightweight separation of concerns:
*   **UI Layer (`ui` package / `MainActivity.kt`):** Compose screens handling state hoisting and user interactions.
*   **Network/API Layer (`data` package):** Singleton objects (`*Api.kt`) utilizing `OkHttpClient`.
*   **Local Storage (`TokenStore.kt`):** Uses `SharedPreferences` to persist the `session_token` and `member_id`.

## 4. Authentication Flow (CRITICAL)
Authentication is the most complex part of this app, primarily handled by `WebLoginApi.kt`.
*   **Web Login Hack:** The official APIs are heavily obfuscated, so the app fakes a Web Login flow.
*   **Methods:** Supports both QR Code polling and Account/Password login.
*   **Encryption:** Passwords are encrypted using AES/CBC/PKCS5Padding (Hardcoded Key: `1E390CMD585LLS4S`, IV: `1111000988761654`) before submission.
*   **Cookie Management:** To bypass strict sub-domain restrictions (`e.welife001.com` vs `a.welife001.com`), a custom `CookieJar` forces all cookies to the root domain (`welife001.com`) and root path (`/`).
*   **Role Selection:** After logging in, the user must select a "Role" (usually `parent` or `teacher`). The app handles the subsequent 302 redirects to extract the final authentication `token` from the URL parameters.

## 5. API Conventions
Once authenticated, the app uses standard JSON POST/GET requests to interact with the backend (`b.welife001.com`).
*   **Headers:** Requests heavily spoof the User-Agent and App versions (e.g., `User-Agent: Dart/3.11 (dart:io)`).
*   **Authorization:** The token obtained from login is passed in the `Authorization` header.
*   **Response Format:** Wrapped in a standard envelope: `{"code": 0, "msg": "...", "data": ...}`. `code == 0` represents success.

## 6. Navigation Routing
The app uses a single-activity architecture (`MainActivity`) with a Compose `NavHost`.
*   **Bottom Navigation:** `home` (首页), `class_list` (班级), `mine` (我的).
*   **Detail Screens:** 
    *   `class_detail/{cid}`: Shows members of a specific class.
    *   `score_detail/{scoreId}`: Shows detailed subject scores for a specific exam.
*   **Parameter Passing:** We pass `NavHostController` directly to main screens to easily navigate to detail screens without deeply nested callback hell.

## 7. Known Quirks for AI Contributors
*   Always use `Dispatchers.IO` for network calls in `*Api.kt` files.
*   Avoid adding redundant paddings to `Scaffold` inner content on detail pages to prevent black bars/edges during transitions.
*   When adding new item types to `HomeScreen`, map the `item.type` in the `HomeCard` UI and expand the click listener navigation logic accordingly (e.g., Type 0 = Task, Type 4 = Score).