package com.yuchenhe.conference.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Creates the first organizer account from BOOTSTRAP_ORGANIZER_USER / _PASSWORD when no such user
 * exists. There is no built-in default password: without both variables nothing is created.
 */
@Component
class BootstrapOrganizer implements ApplicationRunner {
  private static final Logger LOG = LoggerFactory.getLogger(BootstrapOrganizer.class);
  private final AppUserRepository users;
  private final PasswordEncoder encoder;
  private final String username;
  private final String password;

  BootstrapOrganizer(
      AppUserRepository users,
      PasswordEncoder encoder,
      @Value("${app.bootstrap.organizer.username:}") String username,
      @Value("${app.bootstrap.organizer.password:}") String password) {
    this.users = users;
    this.encoder = encoder;
    this.username = username.toLowerCase();
    this.password = password;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (username.isBlank() || password.length() < 12) {
      return;
    }
    if (users.findByUsername(username).isEmpty()) {
      users.save(new AppUser(username, encoder.encode(password), Role.ORGANIZER, null));
      LOG.info("bootstrap organizer account created");
    }
  }
}
