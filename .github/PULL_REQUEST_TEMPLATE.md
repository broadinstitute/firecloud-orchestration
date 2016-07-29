Main PR comment goes here
=========================
```
- [ ] **Submitter**: Rebase to develop. DO NOT SQUASH
- [ ] **Submitter**: Make sure Swagger is updated if API changes
- [ ] **Submitter**: Make sure documentation for code is complete
- [ ] **Submitter**: Make sure liquibase is updated if appropriate
- [ ] **Submitter**: Review code comments; remove done TODOs, create stories for remaining TODOs
- [ ] **Submitter**: Include the JIRA issue number in the PR description
- [ ] **Submitter**: Add description or comments on the PR explaining the hows/whys (if not obvious)
- [ ] **Submitter**: If PR includes new or changed db queries, include the explain plans in the description
- [ ] **Submitter**: Update FISMA documentation if changes to:
  * Authentication
  * Authorization
  * Encryption
  * Audit trails
- [ ] **Submitter**: If you're adding new libraries, sign us up to security updates for them
- [ ] Tell ![](http://i.imgur.com/9dLzbPd.png) that the PR exists if he wants to look at it **(apply requires_doge label)**
- [ ] Anoint a lead reviewer (LR). **Assign PR to LR**
* Review cycle:
  * LR reviews
  * Rest of team may comment on PR at will
  * **LR assigns to submitter** for feedback fixes
  * Submitter rebases to develop again if necessary
  * Submitter makes further commits. DO NOT SQUASH
  * Submitter updates documentation as needed
  * Submitter **reassigns to LR** for further feedback
- [ ] ![](http://i.imgur.com/9dLzbPd.png) sign off
- [ ] **LR** sign off
- [ ] **Assign to submitter** to finalize
- [ ] **Submitter**: Squash commits, rebase if necessary
- [ ] **Submitter**: Verify all tests go green, including CI tests
- [ ] **Submitter**: Merge to develop 
- [ ] **Submitter**: Delete branch after merge
- [ ] **Submitter**: Check configuration files in Jenkins in case they need changes
- [ ] **Submitter**: **Test this change works on dev environment after deployment**. YOU own getting it fixed if dev isn't working for ANY reason!
- [ ] **Submitter**: Verify swagger UI on dev environment still works after deployment
- [ ] **Submitter**: Inform other teams of any API changes via hipchat and/or email
- [ ] **Submitter**: Mark JIRA issue as resolved once this checklist is completed
```
