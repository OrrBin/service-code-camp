package me.pointofreview.api;

import me.pointofreview.core.objects.*;
import me.pointofreview.persistence.UserDataStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@RestController
public class UserAuthentication {
    private final UserDataStore userDataStore;

    @Autowired
    public UserAuthentication(@Qualifier("mongoUserDataStore") UserDataStore userDataStore) {
        this.userDataStore = userDataStore;
    }

    /**
     * Attempt to log in.
     * @param request contains username and password
     * @return {@link User} if username and password match, null otherwise
     * @HttpStatus UNAUTHORIZED - username and password don't match
     * @HttpStatus UNAVAILABLE_FOR_LEGAL_REASONS - user is banned
     */
    @PostMapping("/login")
    public ResponseEntity<User> login(@RequestBody AuthenticationRequest request) {
        if (!userDataStore.checkUsernameAndPassword(request.username, request.password))
            return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);

        var user = userDataStore.getUserByUsername(request.username);

        if (user.getReport().isBanned())
            return new ResponseEntity<>(HttpStatus.UNAVAILABLE_FOR_LEGAL_REASONS);

        return new ResponseEntity<>(user, HttpStatus.OK);
    }

    /**
     * Register a user.
     * Username and password length should be between 3 to 12, and contain only numbers and letters.
     * Username should start with a letter.
     * @param request contains username and password to register
     * @return {@link User} of the created user if succeed, null otherwise
     * @HttpStatus CONFLICT - username already in the system
     * @HttpStatus PRECONDITION_FAILED - invalid username/password format
     * @HttpStatus SERVICE_UNAVAILABLE - generated an existing key (server error)
     */
    @PostMapping("/register")
    public ResponseEntity<User> registerUser(@RequestBody AuthenticationRequest request) {

        if (userDataStore.existsUsername(request.username))
            return new ResponseEntity<>(HttpStatus.CONFLICT);

        if (!isLegalFormat(request.username, request.password))
            return new ResponseEntity<>(HttpStatus.PRECONDITION_FAILED);

        // Create user
        var user = new User(request.username, request.password, UUID.randomUUID().toString(), new Reputation(), new ReportStatus(), new ArrayList<Notification>());
        var created = userDataStore.createUser(user);

        if (!created)
            return new ResponseEntity<>(HttpStatus.SERVICE_UNAVAILABLE); // user id already exists

        return new ResponseEntity<>(user, HttpStatus.OK);
    }

    /**
     * Reports a user.
     * @param username username of user to report, should be a user in the system
     * @param reportType should be spam, badLanguage or misleading
     * @return {@link User} of the userId if succeed
     * @HttpStatus NOT_FOUND - user doesn't exist in the system
     * @HttpStatus METHOD_NOT_ALLOWED - the report type is invalid
     */
    @GetMapping("/report")
    public ResponseEntity<User> reportUser(@RequestParam String username, @RequestParam String reportType) {
        User user = userDataStore.getUserByUsername(username);
        if (user == null) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }

        var reported = userDataStore.addReport(user, reportType);
        if (!reported)
            return new ResponseEntity<>(HttpStatus.METHOD_NOT_ALLOWED);

        return new ResponseEntity<>(user, HttpStatus.OK);
    }

    private boolean isLegalFormat(String username, String password){
        char c = username.toLowerCase().charAt(0);

        // Check user and password are legal
        if (!(username.length() >= 3 && username.length() <= 12))
            return false;

        if (!(password.length() >= 3 && password.length() <= 12))
            return false;

//        if (!(c >= 'a' && c <= 'z')) // username doesn't start with a letter
//            return false;

        for (int i = 1; i < username.length(); i++) {
            c = username.toLowerCase().charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= '0' || c <= '9')))
                return false;
        }

        for (int i = 0; i < password.length(); i++) {
            c = password.toLowerCase().charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= '0' || c <= '9')))
                return false;
        }

        return true;
    }

    @PostMapping("/reputation")
    public ResponseEntity<User> updateUserReputation(@RequestBody ImpressionRequest request) {
        var user = userDataStore.getUserByUsername(request.uploaderName);
        if (user == null){
            return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
        }
        if (!request.uploaderName.equals(request.voterId)) {
            // don't increase reputation if user votes on his own posts
            String sourceId = StringUtils.isEmpty(request.codeReviewSectionId) ? request.snippetId : request.codeReviewSectionId;
            userDataStore.updateUserReputation(user, request.voterId, sourceId, request.impression);
        }
        return new ResponseEntity<>(user, HttpStatus.OK);
    }

    @GetMapping("/reputation")
    public ResponseEntity<Integer> getReputation(@RequestParam String username) {
        var user = userDataStore.getUserByUsername(username);
        if (user == null){
            return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
        }
        Integer reputationScore = user.getReputation().calculate();
        return new ResponseEntity<>(reputationScore, HttpStatus.OK);
    }

    /**
     * Checks the ban status of a user.
     * @param username username of user to check
     * @return true if the user is banned, false otherwise
     * @HttpStatus BAD_REQUEST - user doesn't exist in the system
     */
    @GetMapping("/ban/{username}")
    public ResponseEntity<Boolean> isBanned(@PathVariable(name = "username") String username) {
        var user = userDataStore.getUserByUsername(username);
        if (user == null){
            return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
        }
        boolean isBanned = user.getReport().isBanned();

        return new ResponseEntity<>(isBanned, HttpStatus.OK);
    }

    /**
     * Returns the list of notifications of a user.
     * @param username username of the user
     * @return list of notifications
     * @HttpStatus BAD_REQUEST - user doesn't exist in the system
     */
    @GetMapping("/notifications/{username}")
    public ResponseEntity<List<Notification>> getNotifications(@PathVariable(name = "username") String username) {
        var notifications = userDataStore.getNotificationsByUsername(username);
        if (notifications == null){
            return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
        }
        return new ResponseEntity<>(notifications, HttpStatus.OK);
    }

    /**
     * Adds a notification to the list of notifications of a user.
     * @param username username of the user
     * @return true if successful, false otherwise
     * @HttpStatus BAD_REQUEST - user doesn't exist in the system
     */
    @PostMapping("/notifications/{username}")
    public ResponseEntity<Boolean> addNotification(@PathVariable(name = "username") String username,
                                                               @RequestBody Notification notification) {
        var result = userDataStore.addNotification(username, notification);
        if (!result){
            return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
        }
        return new ResponseEntity<>(true, HttpStatus.OK);
    }
}