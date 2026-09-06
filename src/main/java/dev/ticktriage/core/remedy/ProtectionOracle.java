package dev.ticktriage.core.remedy;

/**
 * Answers "is this location somebody's claim?"
 *
 * <p>Implementations wrap whatever land-protection plugin the server runs. The
 * interface takes plain coordinates so the core package never depends on
 * WorldGuard, GriefPrevention, or Bukkit itself.
 *
 * <p><b>Fail closed.</b> Every implementation must treat an error or an unknown
 * answer as protected. The cost of wrongly skipping a fix is that a lag spike
 * lasts longer; the cost of wrongly clearing inside a claim is somebody's
 * belongings. Those are not comparable.
 */
public interface ProtectionOracle {

    /** Nothing is protected. Used when no claim plugin is installed. */
    ProtectionOracle NONE = new ProtectionOracle() {
        @Override
        public boolean isProtected(String world, int x, int y, int z) {
            return false;
        }

        @Override
        public String describe() {
            return "none";
        }
    };

    /** Everything is protected. Used when a provider fails to initialise and
     *  refusing to act is safer than guessing. */
    ProtectionOracle ALL = new ProtectionOracle() {
        @Override
        public boolean isProtected(String world, int x, int y, int z) {
            return true;
        }

        @Override
        public String describe() {
            return "everything (fail-closed)";
        }
    };

    boolean isProtected(String world, int x, int y, int z);

    /** Short name for logs and the status command. */
    default String describe() {
        return getClass().getSimpleName();
    }
}
