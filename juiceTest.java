package gradle.junit.selenium;

import io.github.bonigarcia.wdm.WebDriverManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JuiceTest {
    private static final String ADDRESS = "localhost";
    private static final String PORT = "3000";
    private static final String BASE_URL = String.format("http://%s:%s", ADDRESS, PORT);
    private static final String TARGET_PRODUCT_NAME = "Apple Pomace";
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(15);

    static WebDriver driver;
    static WebDriverWait wait;
    static Customer customer;

    @BeforeAll
    static void setup() {
        WebDriverManager.chromedriver().setup();
        driver = new ChromeDriver();
        wait = new WebDriverWait(driver, WAIT_TIMEOUT);

        customer = new Customer.Builder()
                .setEmail("selenium-auto@test.com")
                .setPassword("test1234")
                .setSecurityAnswer("test1234")
                .build();

        registerCustomerViaApi(customer);
    }

    @AfterAll
    static void teardown() {
        if (driver != null) {
            driver.quit();
        }
    }

    @Test
    void loginAndPostProductReviewViaUi() {
        dismissWelcomeBanner();
        driver.get(BASE_URL + "/#/login");

        WebElement emailField = wait.until(ExpectedConditions.visibilityOfElementLocated(By.name("email")));
        WebElement passwordField = driver.findElement(By.name("password"));
        WebElement loginButton = driver.findElement(By.id("loginButton"));

        emailField.clear();
        emailField.sendKeys(customer.getEmail());
        passwordField.clear();
        passwordField.sendKeys(customer.getPassword());

        wait.until(ExpectedConditions.elementToBeClickable(loginButton));
        loginButton.click();

        wait.until(webDriver -> ((JavascriptExecutor) webDriver)
                .executeScript("return window.localStorage.getItem('token') != null"));
        wait.until(ExpectedConditions.urlContains("/search"));

        driver.get(BASE_URL + "/#/search?q=" + TARGET_PRODUCT_NAME.replace(" ", "%20"));

        WebElement productImage = wait.until(ExpectedConditions.elementToBeClickable(
                By.cssSelector("img[alt='" + TARGET_PRODUCT_NAME + "']")));
        productImage.click();

        wait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector("mat-dialog-content")));

        String reviewMessage = "UI review " + UUID.randomUUID();

        WebElement reviewField = wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector("textarea[aria-label='Text field to review a product']")));
        reviewField.clear();
        reviewField.sendKeys(reviewMessage);
        JavascriptExecutor js = (JavascriptExecutor) driver;
        js.executeScript(
                "arguments[0].value = arguments[1]; arguments[0].dispatchEvent(new Event('input', { bubbles: true }));",
                reviewField,
                reviewMessage);

        dismissCookieBanner();

        WebElement submitReviewButton = wait.until(ExpectedConditions.elementToBeClickable(
                By.cssSelector("#submitButton:not([disabled])")));
        submitReviewButton.click();

        WebElement reviewsPanel = wait.until(ExpectedConditions.elementToBeClickable(
                By.cssSelector("mat-expansion-panel[aria-label='Expand for Reviews'] mat-expansion-panel-header")));
        reviewsPanel.click();

        WebElement postedReview = wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.xpath("//mat-dialog-content//p[contains(text(), '" + reviewMessage + "')]")));
        assertTrue(postedReview.isDisplayed(), "Expected review to appear in product dialog");
    }

    @Test
    void loginAndPostProductReviewViaApi() {
        int productId = findProductId(TARGET_PRODUCT_NAME);

        String token = given()
                .contentType("application/json")
                .body(Map.of(
                        "email", customer.getEmail(),
                        "password", customer.getPassword()))
                .when()
                .post(BASE_URL + "/rest/user/login")
                .then()
                .statusCode(200)
                .extract()
                .path("authentication.token");

        customer.saveToken(token);

        String reviewMessage = "API review " + UUID.randomUUID();

        given()
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .body(Map.of(
                        "message", reviewMessage,
                        "author", customer.getEmail()))
                .when()
                .put(BASE_URL + "/rest/products/" + productId + "/reviews")
                .then()
                .statusCode(201);

        given()
                .when()
                .get(BASE_URL + "/rest/products/" + productId + "/reviews")
                .then()
                .statusCode(200)
                .body("status", equalTo("success"))
                .body("data.message", hasItem(reviewMessage));
    }

    private static int findProductId(String productName) {
        return given()
                .queryParam("q", productName)
                .when()
                .get(BASE_URL + "/rest/products/search")
                .then()
                .statusCode(200)
                .body("status", equalTo("success"))
                .body("data[0].name", equalTo(productName))
                .extract()
                .path("data[0].id");
    }

    private static void registerCustomerViaApi(Customer customer) {
        String registrationBody = """
                {
                  "email": "%s",
                  "password": "%s",
                  "passwordRepeat": "%s",
                  "securityQuestion": {
                    "id": 1,
                    "question": "Your eldest siblings middle name?",
                    "createdAt": "2017-11-11 04:46:47.568 +00:00",
                    "updatedAt": "2017-11-11 04:46:47.568 +00:00"
                  },
                  "securityAnswer": "%s"
                }
                """.formatted(
                customer.getEmail(),
                customer.getPassword(),
                customer.getPassword(),
                customer.getSecurityAnswer());

        int statusCode = given()
                .contentType("application/json")
                .body(registrationBody)
                .when()
                .post(BASE_URL + "/api/Users")
                .then()
                .extract()
                .statusCode();

        if (statusCode != 201 && statusCode != 400) {
            throw new IllegalStateException("Unexpected registration status: " + statusCode);
        }
    }

    private static void dismissWelcomeBanner() {
        driver.get(BASE_URL);
        JavascriptExecutor js = (JavascriptExecutor) driver;
        js.executeScript("window.localStorage.setItem('welcomebanner_status', 'dismissed');");
        js.executeScript("window.localStorage.setItem('cookieconsent_status', 'dismiss');");
        dismissCookieBanner();
    }

    private static void dismissCookieBanner() {
        try {
            WebElement dismissButton = new WebDriverWait(driver, Duration.ofSeconds(3))
                    .until(ExpectedConditions.elementToBeClickable(By.cssSelector(".cc-window .cc-dismiss")));
            dismissButton.click();
            wait.until(ExpectedConditions.invisibilityOfElementLocated(By.cssSelector(".cc-window")));
        } catch (Exception ignored) {
            // Cookie banner is not shown for this session.
        }
    }
}
