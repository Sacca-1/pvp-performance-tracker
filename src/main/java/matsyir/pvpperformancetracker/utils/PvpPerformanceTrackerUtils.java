package matsyir.pvpperformancetracker.utils;

import lombok.extern.slf4j.Slf4j;
import matsyir.pvpperformancetracker.models.AnimationData;
import net.runelite.api.HeadIcon;
import net.runelite.api.PlayerComposition;
import net.runelite.api.Skill;
import net.runelite.api.SpriteID;

import java.util.Arrays;

@Slf4j
public class PvpPerformanceTrackerUtils
{
	/**
	 * Calculates the chance of knocking out an opponent with a single hit.
	 *
	 * @param accuracy           The attacker's accuracy (0.0 to 1.0).
	 * @param minHit             The attacker's minimum possible hit.
	 * @param maxHit             The attacker's maximum possible hit.
	 * @param estimatedOpponentHp The estimated HP of the opponent before the hit.
	 * @return The KO chance (0.0 to 1.0), or null if the max hit is less than the opponent's HP.
	 */
	public static Double calculateKoChance(double accuracy, int minHit, int maxHit, int estimatedOpponentHp)
	{
		// Cannot KO if max hit is less than opponent's current HP.
		if (maxHit < estimatedOpponentHp || estimatedOpponentHp <= 0)
		{
			return null;
		}

		// Ensure minHit is not greater than maxHit (shouldn't happen, but safety check)
		// and not negative.
		minHit = Math.max(0, Math.min(minHit, maxHit));

		int totalPossibleHits = maxHit - minHit + 1;
		if (totalPossibleHits <= 0)
		{
			// Avoid division by zero if minHit somehow exceeds maxHit after clamping.
			log.warn("Calculated totalPossibleHits <= 0 (min: {}, max: {})", minHit, maxHit);
			return null;
		}

		// Number of hits that would result in a KO (damage >= opponent's HP).
		// Clamp estimatedOpponentHp to be at least minHit for calculation,
		// otherwise koHits could be larger than totalPossibleHits.
		int effectiveHpForCalc = Math.max(minHit, estimatedOpponentHp);
		int koHits = maxHit - effectiveHpForCalc + 1;

		// Ensure koHits is not negative (if effectiveHpForCalc > maxHit somehow)
		koHits = Math.max(0, koHits);

		// KO chance = Accuracy * (Number of KO hits / Total possible hits)
		double chance = accuracy * ((double) koHits / totalPossibleHits);

		// Clamp chance between 0 and 1 (due to potential floating point inaccuracies)
		return Math.max(0.0, Math.min(chance, 1.0));
	}

	/**
	 * Returns how many splats an attack animation should produce based on its group pattern.
	 */
	public static int getExpectedHits(AnimationData data)
	{
		if (data == null)
		{
			return 1;
		}
		// Sum the hitsplat groups defined in AnimationData
		return Arrays.stream(data.getHitsplatGroupPattern()).sum();
	}

	/**
	 * Calculates the opponent's HP before a hit based on their health bar ratio/scale after the hit.
	 *
	 * @param ratio      The opponent's health bar ratio after the hit (0-?).
	 * @param scale      The opponent's health bar scale.
	 * @param maxHp      The opponent's maximum HP.
	 * @param damageSum  The total damage dealt by the hitsplat(s).
	 * @return The estimated HP before the hit, or -1 if calculation is not possible.
	 */
	public static int calculateHpBeforeHit(int ratio, int scale, int maxHp, int damageSum)
	{
		if (ratio < 0 || scale <= 0 || maxHp <= 0)
		{
			return -1; // Cannot estimate
		}

		int hpAfter;
		if (ratio == 0)
		{
			hpAfter = 0;
		}
		else
		{
			int minHealth = 1;
			int maxHealth;
			if (scale > 1)
			{
				if (ratio > 1)
				{
					minHealth = (maxHp * (ratio - 1) + scale - 2) / (scale - 1);
				}
				maxHealth = (maxHp * ratio - 1) / (scale - 1);
				if (maxHealth > maxHp)
				{
					maxHealth = maxHp;
				}
			}
			else // scale = 1 implies ratio must be 1
			{
				maxHealth = maxHp;
			}
			hpAfter = (minHealth + maxHealth + 1) / 2; // Average the possible range
		}

		return hpAfter + damageSum;
	}

	/**
	 * Attempt-level KO probability for Dragon Claws special across two ticks, using observed inter-tick healing.
	 *
	 * Inputs are the spec-level accuracy and maxHit from PvpDamageCalc (where claws set max = 2*base + 1 and
	 * accuracy = 1 - (1 - a)^4). We derive the base per-swing accuracy 'a' and base max 'M', then sum over
	 * all base rolls H in [0..M] and the first-connect case (E1..E4) to determine D1 (tick k) and D2 (tick k+1).
	 * We then compute: KO = sum_{H,case} P(H,case) * [ D1 >= hpBefore ? 1 : (D2 >= hpBefore - D1 + healBetween ? 1 : 0) ].
	 */
	public static Double calculateClawsTwoPhaseKo(double specAccuracy, int specMaxHit, int hpBefore, int healBetween)
	{
		if (specMaxHit <= 0 || hpBefore <= 0)
		{
			return null;
		}

		// Clamp inputs
		double accSpec = Math.max(0.0, Math.min(1.0, specAccuracy));
		int M = Math.max(0, (specMaxHit - 1) / 2); // base single-swing max

		if (M <= 0)
		{
			return null;
		}

		// Derive base per-swing accuracy 'a' from spec-level accuracy: accSpec = 1 - (1 - a)^4
		double a;
		if (accSpec <= 0)
		{
			a = 0.0;
		}
		else if (accSpec >= 1.0)
		{
			a = 1.0;
		}
		else
		{
			a = 1.0 - Math.pow(1.0 - accSpec, 0.25);
		}
		double q = 1.0 - a;
		double p1 = a;
		double p2 = q * a;
		double p3 = q * q * a;
		double p4 = q * q * q * a;

		double invCount = 1.0 / (M + 1); // uniform over H in [0..M]
		double ko = 0.0;

		for (int H = 0; H <= M; H++)
		{
			// Precompute fragments
			int halfCeil = (H + 1) / 2;
			int halfFloor = H / 2;
			int quarterFloor = H / 4;
			int threeQuarterCeil = (int) Math.ceil(0.75 * H);
			int threeQuarterFloor = (int) Math.floor(0.75 * H);

			// E1: first connects
			{
				int h1 = H;
				int h2 = halfCeil;
				int h3 = quarterFloor;
				int used = h1 + h2 + h3;
				int r = Math.max(0, 2 * H - used);
				int D1 = h1 + h2;
				int D2 = h3 + r;
				double term = p1 * invCount * ((D1 >= hpBefore) ? 1.0 : ((D2 >= (hpBefore - D1 + healBetween)) ? 1.0 : 0.0));
				ko += term;
			}

			// E2: second connects
			{
				int D1 = H; // tick k sum
				int D2 = H; // tick k+1 sum = ceil(H/2)+floor(H/2)
				double term = p2 * invCount * ((D1 >= hpBefore) ? 1.0 : ((D2 >= (hpBefore - D1 + healBetween)) ? 1.0 : 0.0));
				ko += term;
			}

			// E3: third connects (first two miss) -> all on tick k+1
			{
				int D1 = 0;
				int D2 = threeQuarterCeil + threeQuarterFloor; // ~ round(1.5 * H)
				double term = p3 * invCount * ((D1 >= hpBefore) ? 1.0 : ((D2 >= (hpBefore - D1 + healBetween)) ? 1.0 : 0.0));
				ko += term;
			}

			// E4: fourth connects (first three miss) -> all on tick k+1
			{
				int D1 = 0;
				int D2 = threeQuarterCeil + threeQuarterFloor; // same total
				double term = p4 * invCount * ((D1 >= hpBefore) ? 1.0 : ((D2 >= (hpBefore - D1 + healBetween)) ? 1.0 : 0.0));
				ko += term;
			}
		}

		// Clamp numerical noise
		if (ko < 0.0) ko = 0.0;
		if (ko > 1.0) ko = 1.0;
		return ko;
	}

    public static int getSpriteForSkill(Skill skill)
    {
        switch (skill)
        {
            case ATTACK: return SpriteID.SKILL_ATTACK;
            case STRENGTH: return SpriteID.SKILL_STRENGTH;
            case DEFENCE: return SpriteID.SKILL_DEFENCE;
            case RANGED: return SpriteID.SKILL_RANGED;
            case MAGIC: return SpriteID.SKILL_MAGIC;
            case HITPOINTS: return SpriteID.SKILL_HITPOINTS;
            default: return -1;
        }
    }

	// returns SpriteID for a given HeadIcon. returns -1 if not found
	public static int getSpriteForHeadIcon(HeadIcon icon)
	{
		if (icon == null) { return -1; }
		switch (icon)
		{
			case MELEE: return SpriteID.PRAYER_PROTECT_FROM_MELEE;
			case RANGED: return SpriteID.PRAYER_PROTECT_FROM_MISSILES;
			case MAGIC: return SpriteID.PRAYER_PROTECT_FROM_MAGIC;
			case SMITE: return SpriteID.PRAYER_SMITE;
			case RETRIBUTION: return SpriteID.PRAYER_RETRIBUTION;
			case REDEMPTION: return SpriteID.PRAYER_REDEMPTION;
			default: return -1;
		}
	}

	// fix an itemId that came from getPlayerComposition().getEquipmentIds()
	public static int fixItemId(int itemId)
	{
		return itemId > PlayerComposition.ITEM_OFFSET ? itemId - PlayerComposition.ITEM_OFFSET : itemId;
	}

	// create new array so we don't modify original array
	public static int[] fixItemIds(int[] itemIds)
	{
		if (itemIds == null || itemIds.length < 1)
		{
			return new int[] { 0 };
		}
		int[] fixedItemIds = new int[itemIds.length];
		for (int i = 0; i < itemIds.length; i++)
		{
			fixedItemIds[i] = fixItemId(itemIds[i]);
		}

		return fixedItemIds;
	}
}
