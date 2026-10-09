// Creating a room draws it from the create's own read-back, then refreshes every device's room in the
// background with a visible status (Gordon, 2026-10-10: the room appeared ~9 s after "Creating..." cleared).
String source = new File('apps/automation_map.groovy').getText('UTF-8')
def AppSource = new GroovyClassLoader(this.class.classLoader).parseClass(new File('tests/support/AppSource.groovy'))
String create = AppSource.function(source, 'roomPlanCreateRoom')
assert create.contains('List after = hubRoomList()') && create.contains('rooms: after') : 'the create no longer returns its read-back room list'
int crud = source.indexOf('function roomPlanCrud(')
String crudBody = source.substring(crud, source.indexOf('\n}\n', crud))
assert crudBody.indexOf('ROOMPLAN.rooms = d.rooms; roomPlanRender();') < crudBody.indexOf('roomPlanRefreshLive(keep)') : 'the room is drawn only after the full reload'
assert crudBody.contains("'Updating from the hub...'") : 'the background reload is silent again'
println 'ok   a new room is drawn from the create answer, and the reload says it is running'
