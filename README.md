# Nimble Modulith

> Модульний моноліт на .NET 10 з використанням .NET Aspire, FastEndpoints, Mediator та Entity Framework Core.

## Зміст

- [Опис проекту](#опис-проекту)
- [Архітектура](#архітектура)
- [Технологічний стек](#технологічний-стек)
- [Інструкція запуску](#інструкція-запуску)
- [Модулі системи](#модулі-системи)
- [API Endpoints](#api-endpoints)
- [Архітектурні рішення](#архітектурні-рішення)

---

## Опис проекту

Nimble Modulith — це демонстраційна система управління замовленнями, побудована за патерном **Modular Monolith**. Система об'єднує функціональність управління користувачами, продуктами, клієнтами, замовленнями, електронною поштою та звітністю в єдиному розгортуваному додатку, поділеному на чітко відокремлені модулі.

Проект демонструє, як можна отримати переваги мікросервісної архітектури (слабка зв'язаність, незалежна еволюція модулів) без операційної складності розподілених систем.

---

## Архітектура

Система побудована як **Modular Monolith** з наступними принципами:

- Кожен модуль має власну базу даних (schema isolation)
- Міжмодульна комунікація відбувається виключно через **Mediator** (команди та події)
- Модулі не мають прямих посилань один на одного — лише на `*.Contracts` проекти
- Оркестрація інфраструктури через **.NET Aspire**

### Діаграми

Діаграми у форматі C4 Model знаходяться у папці [`/docs`](./docs/):

| Рівень | Файл | Опис |
|--------|------|------|
| Level 1 – System Context | [`docs/c4-level1-context.puml`](./docs/c4-level1-context.puml) | Система та її зовнішні актори |
| Level 2 – Container | [`docs/c4-level2-containers.puml`](./docs/c4-level2-containers.puml) | Контейнери: сервіси та бази даних |
| Level 3 – Component | [`docs/c4-level3-components.puml`](./docs/c4-level3-components.puml) | Внутрішня структура Web API |

---

## Технологічний стек

| Технологія | Призначення |
|-----------|-------------|
| **.NET 10** | Цільовий фреймворк |
| **.NET Aspire** | Оркестрація та service discovery |
| **FastEndpoints** | HTTP API endpoints (замість MVC Controllers) |
| **Mediator (Source Generator)** | CQRS та міжмодульна комунікація |
| **Entity Framework Core 10** | ORM для доступу до даних |
| **ASP.NET Core Identity** | Автентифікація та авторизація |
| **Ardalis.Specification** | Repository pattern зі специфікаціями |
| **Ardalis.Result** | Уніфіковані результати операцій |
| **Dapper** | Швидкі SQL-запити для звітності |
| **MailKit** | Відправлення електронної пошти |
| **Serilog** | Структуроване логування |
| **SQL Server** | Основна база даних |
| **Papercut SMTP** | Локальний SMTP-сервер для розробки |

---

## Інструкція запуску

### Передумови

- [.NET 10 SDK](https://dotnet.microsoft.com/download/dotnet/10.0)
- [Docker Desktop](https://www.docker.com/products/docker-desktop/) (для SQL Server та Papercut через Aspire)
- [.NET Aspire workload](https://learn.microsoft.com/en-us/dotnet/aspire/fundamentals/setup-tooling)

```bash
dotnet workload install aspire
```

### Запуск через .NET Aspire (рекомендовано)

```bash
# Клонуємо репозиторій
git clone https://github.com/your-username/practicum-2-X.git
cd practicum-2-X

# Запускаємо AppHost (запустить SQL Server, Papercut та Web API автоматично)
dotnet run --project Nimble.Modulith.AppHost
```

Після запуску відкриється **Aspire Dashboard** за адресою `https://localhost:17004`, де можна переглянути стан усіх сервісів, логи та трейси.

### Запуск тільки Web API (без Aspire)

```bash
# Налаштуйте рядки підключення в appsettings.Development.json
dotnet run --project Nimble.Modulith.Web
```

### Swagger UI

Після запуску API документація доступна за адресою:
```
http://localhost:5199/swagger
```

### Тестовий адміністратор

При першому запуску автоматично створюється адміністратор:
- **Email:** `admin@myapp.com`
- **Password:** `Admin123!`

### Перегляд електронних листів (розробка)

Papercut SMTP UI доступний за адресою: `http://localhost:37408`

---

## Модулі системи

### Users Module
Управління користувачами та автентифікація через ASP.NET Core Identity.
- Реєстрація, вхід, вихід
- Управління ролями (Admin, Customer)
- Скидання паролів

### Products Module
Каталог продуктів.
- CRUD операції над продуктами
- Запит деталей продукту для інших модулів через `GetProductDetailsQuery`

### Customers Module
Управління клієнтами та замовленнями.
- Реєстрація клієнтів (автоматично створює користувача)
- Повний цикл замовлення: створення → додавання товарів → підтвердження
- Авторизація на рівні власника даних

### Email Module
Асинхронна відправка електронної пошти через Channel-based queue.
- Черга повідомлень через `System.Threading.Channels`
- Background worker для відправки
- Інтеграція через `SendEmailCommand`

### Reporting Module
Аналітична звітність на основі star schema.
- Ingestion даних через `OrderCreatedEvent`
- Звіти по замовленнях, продажах, клієнтах
- Підтримка CSV та JSON форматів виводу
- Dapper для оптимізованих аналітичних запитів

---

## API Endpoints

### Автентифікація
| Метод | URL | Опис |
|-------|-----|------|
| POST | `/register` | Реєстрація нового користувача |
| POST | `/login` | Вхід (повертає JWT токен) |
| POST | `/logout` | Вихід |
| POST | `/users/reset-password` | Скидання паролю |

### Продукти
| Метод | URL | Опис |
|-------|-----|------|
| GET | `/products` | Список продуктів |
| GET | `/products/{id}` | Деталі продукту |
| POST | `/products` | Створити продукт (Admin) |
| PUT | `/products/{id}` | Оновити продукт (Admin) |
| DELETE | `/products/{id}` | Видалити продукт (Admin) |

### Клієнти
| Метод | URL | Опис |
|-------|-----|------|
| GET | `/customers` | Список клієнтів (Admin) |
| GET | `/customers/{id}` | Деталі клієнта |
| POST | `/customers` | Створити клієнта |

### Замовлення
| Метод | URL | Опис |
|-------|-----|------|
| GET | `/orders/{id}` | Деталі замовлення |
| GET | `/orders/by-date/{date}` | Замовлення за датою (Admin) |
| POST | `/orders` | Створити замовлення |
| POST | `/orders/{id}/items` | Додати товар |
| DELETE | `/orders/{id}/items/{itemId}` | Видалити товар |
| POST | `/orders/{id}/confirm` | Підтвердити замовлення |

### Звіти
| Метод | URL | Опис |
|-------|-----|------|
| GET | `/reports/orders` | Звіт по замовленнях |
| GET | `/reports/product-sales` | Звіт по продажах |
| GET | `/reports/customers/{id}/orders` | Звіт по клієнту |

> Додайте `?format=csv` або заголовок `Accept: text/csv` для отримання CSV.

---

## Архітектурні рішення

### 1. Modular Monolith замість мікросервісів

**Рішення:** єдиний процес з логічно відокремленими модулями.

**Обґрунтування:** На ранніх стадіях розробки складність оперування мікросервісами (distributed tracing, network failures, eventual consistency) переважує переваги. Модульний моноліт дає чисту структуру коду та можливість пізнішого виокремлення в сервіси без переписування логіки.

### 2. CQRS через Mediator (Source Generator)

**Рішення:** Усі операції реалізовані як команди (`ICommand<T>`) та запити (`IQuery<T>`).

**Обґрунтування:** Mediator.SourceGenerator генерує dispatch-код під час компіляції (без reflection), що дає нульовий runtime overhead порівняно з MediatR. CQRS розділяє відповідальність між читанням та записом.

### 3. Isolation через окремі бази даних

**Рішення:** Кожен модуль має власний DbContext та власну SQL-схему (`Users`, `Products`, `Customers`, `Reporting`).

**Обґрунтування:** Повна ізоляція схем унеможливлює "зв'язування через JOIN" між модулями. Кожен модуль може мігрувати незалежно.

### 4. Міжмодульна комунікація через Contracts

**Рішення:** Модулі спілкуються лише через `*.Contracts` проекти з командами та запитами Mediator.

**Обґрунтування:** Явний контракт замість прямих посилань на доменні моделі інших модулів. При виокремленні в мікросервіси — контракти стають API-специфікацією.

### 5. Star Schema для Reporting

**Рішення:** Окрема база даних з таблицями `FactOrders`, `DimCustomer`, `DimProduct`, `DimDate`.

**Обґрунтування:** OLTP і OLAP мають різні оптимізаційні вимоги. Star schema дозволяє ефективні аналітичні запити без навантаження на операційні таблиці. Dapper замість EF Core для raw SQL звітів.

### 6. Асинхронна черга для Email

**Рішення:** `System.Threading.Channels` + `BackgroundService` замість синхронної відправки.

**Обґрунтування:** SMTP-з'єднання повільні. Відправка в фоні дозволяє HTTP-запитам повертатися миттєво, не блокуючи thread pool.

### 7. FastEndpoints замість MVC Controllers

**Рішення:** Кожен endpoint — окремий клас, що наслідує `Endpoint<TReq, TRes>`.

**Обґрунтування:** REPR (Request-Endpoint-Response) патерн дає кращу читабельність та тестованість, ніж товсті контролери. Автоматична валідація, Swagger-документація та JWT вбудовані в FastEndpoints.

---

## Структура проекту

```
Nimble.Modulith/
├── Nimble.Modulith.AppHost/          # .NET Aspire оркестратор
├── Nimble.Modulith.ServiceDefaults/  # Спільні налаштування (OpenTelemetry, Health Checks)
├── Nimble.Modulith.Web/              # Точка входу Web API
│
├── Nimble.Modulith.Users/            # Модуль користувачів
├── Nimble.Modulith.Users.Contracts/  # Публічний контракт модуля Users
│
├── Nimble.Modulith.Products/         # Модуль продуктів
├── Nimble.Modulith.Products.Contracts/
│
├── Nimble.Modulith.Customers/        # Модуль клієнтів та замовлень
├── Nimble.Modulith.Customers.Contracts/
│
├── Nimble.Modulith.Email/            # Модуль електронної пошти
├── Nimble.Modulith.Email.Contracts/
│
├── Nimble.Modulith.Reporting/        # Модуль звітності
│
└── docs/                             # C4 діаграми
    ├── c4-level1-context.puml
    ├── c4-level2-containers.puml
    └── c4-level3-components.puml
```
