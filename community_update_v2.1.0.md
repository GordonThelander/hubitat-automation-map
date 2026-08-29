Automation Map 2.1.0 is out, upgrading from 2.0.4.

Scanning is faster - roughly two minutes down to well under a minute on a similarly sized hub, from bulk device listing and concurrent discovery instead of the old sequential walk. Nothing to configure, it's just faster.

Hub Variables now come from an authoritative inventory read directly from the hub, instead of only picking up what decoded rules happened to reference. Variable Connector devices are linked to the variables they represent, and a bug where a variable name with a trailing period silently dropped its read/write relationships is fixed.

Insights now gives a plain-language explanation for each finding - what it means, when it's normal, and what to check next - instead of just a count and a label. The same explanations are included in the AI-friendly export.

External Systems ships with reviewed defaults for common integrations (Hue, LIFX, Sensibo, Tapo, Meross, Chromecast, Google Home, and others), so they're classified out of the box instead of sitting unassessed. Your own declarations still override anything reviewed or matched from the registry.

Every hub-discovered device now appears in the graph and export, including ones nothing references, so the counts shown match what's actually on the hub.

Minimum platform is now 2.5.1, up from 2.3.0 - that's what this build was tested against. The map's internal data format also changed since 2.0.4, so after updating the app will ask for one scan before it shows anything. That's expected.

Post here or open a GitHub issue if you hit something.
