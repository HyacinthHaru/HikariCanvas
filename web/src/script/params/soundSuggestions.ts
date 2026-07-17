/**
 * playSound 积木的常用声音建议列表（datalist 候选）。
 *
 * <p>声音 id = {@code <input list>} 文本输入 + 常用建议 ~24 个（前端硬编码常量）；
 * 用户可从下拉里挑常用声音，也可手填任意合法
 * Minecraft sound id（如 {@code minecraft:entity.cat.purr}）——本列表只是"快捷建议"，
 * <b>不是白名单</b>（后端按 Registry 校验）。</p>
 *
 * <p>每项带一个 i18n labelKey（指向 {@code script.soundNames.*}），datalist 选项显示
 * 「id —— 中文名」帮玩家认。labelKey 缺失 / 未翻译时退回显示 id（resolveLabelKey 行为）。</p>
 */

import { resolveLabelKey } from '../canvas/labelKey';

/** 单条声音建议：id（上 wire）+ labelKey（友好名）。 */
export interface SoundSuggestion {
    /** Minecraft sound id（小写点分，省略 {@code minecraft:} 前缀，后端会补全 namespace）。 */
    id: string;
    /** 友好名 i18n key（{@code script.soundNames.*}）。 */
    labelKey: string;
}

/**
 * ~24 个常用声音（UI / 提示音 / 互动 / 氛围）。覆盖最常被招牌脚本用到的场景：
 * 点击反馈、升级 / 经验、音符盒乐器、铁砧 / 末影龙等戏剧性音效。
 */
export const SOUND_SUGGESTIONS: readonly SoundSuggestion[] = [
    // —— UI / 反馈 ——
    { id: 'ui.button.click', labelKey: 'script.soundNames.uiButtonClick' },
    { id: 'block.note_block.harp', labelKey: 'script.soundNames.noteHarp' },
    { id: 'block.note_block.bell', labelKey: 'script.soundNames.noteBell' },
    { id: 'block.note_block.pling', labelKey: 'script.soundNames.notePling' },
    { id: 'block.note_block.bass', labelKey: 'script.soundNames.noteBass' },
    { id: 'block.note_block.banjo', labelKey: 'script.soundNames.noteBanjo' },
    { id: 'block.note_block.chime', labelKey: 'script.soundNames.noteChime' },
    // —— 玩家 / 经验 ——
    { id: 'entity.experience_orb.pickup', labelKey: 'script.soundNames.xpOrbPickup' },
    { id: 'entity.player.levelup', labelKey: 'script.soundNames.playerLevelup' },
    { id: 'entity.item.pickup', labelKey: 'script.soundNames.itemPickup' },
    { id: 'entity.player.hurt', labelKey: 'script.soundNames.playerHurt' },
    // —— 互动 / 机关 ——
    { id: 'block.anvil.land', labelKey: 'script.soundNames.anvilLand' },
    { id: 'block.anvil.use', labelKey: 'script.soundNames.anvilUse' },
    { id: 'block.lever.click', labelKey: 'script.soundNames.leverClick' },
    { id: 'block.dispenser.dispense', labelKey: 'script.soundNames.dispenserDispense' },
    { id: 'block.bell.use', labelKey: 'script.soundNames.bellUse' },
    { id: 'block.chest.open', labelKey: 'script.soundNames.chestOpen' },
    { id: 'block.beacon.activate', labelKey: 'script.soundNames.beaconActivate' },
    // —— 戏剧 / 提示 ——
    { id: 'entity.ender_dragon.growl', labelKey: 'script.soundNames.enderDragonGrowl' },
    { id: 'entity.wither.spawn', labelKey: 'script.soundNames.witherSpawn' },
    { id: 'entity.lightning_bolt.thunder', labelKey: 'script.soundNames.lightningThunder' },
    { id: 'entity.firework_rocket.launch', labelKey: 'script.soundNames.fireworkLaunch' },
    { id: 'entity.villager.yes', labelKey: 'script.soundNames.villagerYes' },
    { id: 'entity.villager.no', labelKey: 'script.soundNames.villagerNo' },
];

/**
 * 取某声音建议的展示文案（datalist option 文本）。i18n 缺失 → 退 id 本身。
 *
 * @param s    建议项
 * @param root 当前 locale 的 messages 对象（{@code t.value}）
 */
export function soundLabelOf(s: SoundSuggestion, root: unknown): string {
    const friendly = resolveLabelKey(root, s.labelKey);
    // 命中翻译 → 「id —— 中文名」；未命中（resolveLabelKey 返回 key 本身）→ 只显 id。
    return friendly === s.labelKey ? s.id : `${s.id} — ${friendly}`;
}
