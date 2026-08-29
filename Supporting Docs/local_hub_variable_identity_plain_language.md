# Why Local and Hub Variable Identification Is Difficult

Hubitat does not clearly record whether a rule is referring to a Local Variable or a Hub Variable.

Both types can use the same internal fields, and they can even have the same name:

- A Local Variable belongs only to one rule.
- A Hub Variable is shared across the entire hub.
- A Variable Connector makes a Hub Variable also appear as a device.

Automation Map therefore cannot simply see the name `Example` and know which `Example` the rule means. Guessing incorrectly could create false relationships, combine unrelated variables, or count the same thing twice.

The distinction affects several connected parts of Automation Map:

- scanning and decoding the rule;
- deciding the variable's identity;
- labelling actions correctly;
- drawing Hub Variable relationships;
- keeping Local Variables inside their owning rules;
- showing uncertain references honestly;
- producing the AI-friendly export;
- ensuring no variable values are exposed.

The safest answer is sometimes "the scope cannot be determined" rather than pretending the reference is a Hub Variable. Supporting that answer requires replacing several older assumptions while ensuring existing Hub Variable maps, Variable Connectors, exports, and scans continue working correctly.

## Short version

Hubitat stores Local and Hub Variable references in very similar undocumented structures. Because the same name can exist in both places, Automation Map must use the owning rule and authoritative Hub Variable inventory to separate them. When the stored information is not enough to prove which one was intended, the app reports the reference as uncertain instead of drawing a convincing but potentially false relationship.
