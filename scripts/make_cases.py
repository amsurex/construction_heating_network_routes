import json
from pathlib import Path

from pyproj import Transformer
from shapely.geometry import LineString, Point, box, mapping
from shapely.ops import transform

TO_WGS = Transformer.from_crs(32637, 4326, always_xy=True).transform
OX, OY = 414000.0, 6173000.0
OUT = Path("data/cases")


def feat(fid, obj_type, geom, **props):
    p = {"id": fid, "object_type": obj_type}
    p.update(props)
    return {"type": "Feature", "properties": p, "geometry": mapping(transform(TO_WGS, geom))}


def P(x, y):
    return Point(OX + x, OY + y)


def L(*pts):
    return LineString([(OX + x, OY + y) for x, y in pts])


def B(x1, y1, x2, y2):
    return box(OX + x1, OY + y1, OX + x2, OY + y2)


def base_network(y=0.0, x1=-150, x2=150, chamber_x=0.0, dn=300):
    """Существующий участок вдоль оси X с камерой в точке chamber_x и источником."""
    return [
        feat("src", "source", P(x2 + 30, y), name="источник"),
        feat("net_1", "heat_network", L((x1, y), (chamber_x, y)), diameter=dn),
        feat("net_2", "heat_network", L((chamber_x, y), (x2 + 30, y)), diameter=dn),
        feat("ch_1", "heat_chamber", P(chamber_x, y)),
    ]


CASES = []


def case(name, title, rule, expect, features, mode="PLAN_2D"):
    CASES.append({"name": name, "title": title, "rule": rule, "expect": expect, "mode": mode})
    fc = {"type": "FeatureCollection", "name": name, "features": features}
    (OUT / f"{name}.geojson").write_text(json.dumps(fc, ensure_ascii=False), encoding="utf-8")


def main():
    OUT.mkdir(parents=True, exist_ok=True)

    case("01_spec_example", "Пример из Техприложения §7.3",
         "§7.3, §3, §6: 100 м ДУ100 = 8 974 800 ₽, врезка в камеру 5 000 000 ₽, score 0.6913",
         "один прямой участок ДУ100 от камеры ch_1 к точке, cost 8 974 800, construction_cost 13 974 800, score 0.6913",
         base_network() + [feat("oks_1", "oks_connection_point", P(0, 100), flow_tph=20.0)])

    case("02_building_detour", "Обход здания с отступом 5 м",
         "табл. 2: ОКС — пересечение запрещено, отступ 5 м при ДУ<500 (от габарита)",
         "трасса огибает здание, ближе 5.3 м к стене не подходит, 2 поворота; финальный участок — в своё здание через ближайшую стену",
         base_network() + [
             feat("bld_obstacle", "restriction", B(-40, 30, 40, 70), restriction_type="oks"),
             feat("bld_target", "restriction", B(-20, 110, 20, 140), restriction_type="oks"),
             feat("oks_1", "oks_connection_point", P(0, 125), flow_tph=20.0)])

    case("03_road_crossing", "Спецпроход через дорогу",
         "табл. 2, §4: дорога — спецпроход одним прямым участком под углом ≥45°, спецзона = полигон + 3 м, Kспец 1.60, техузлы на границах",
         "3 участка base/special/base, 2 техузла, спецучасток 26 м (20 + 3 + 3) с K=1.6",
         base_network() + [
             feat("road_1", "restriction", B(-300, 40, 300, 60), restriction_type="road"),
             feat("oks_1", "oks_connection_point", P(0, 100), flow_tph=20.0)])

    case("04_shared_trunk", "Общая магистраль вместо двух врезок",
         "§2.1, §3.2: разветвление только в камере; камера-разветвление 3 млн при ДУ≤200 дешевле второй врезки 5 млн",
         "одна магистраль ДУ125 (40 т/ч), одно присоединение к сети, камера-разветвление, две ветки ДУ100 — вместо двух врезок",
         base_network() + [
             feat("oks_a", "oks_connection_point", P(-15, 300), flow_tph=20.0),
             feat("oks_b", "oks_connection_point", P(15, 300), flow_tph=20.0)])

    case("05_courtyard", "Точка во дворе П-образного дома",
         "§2.2: финальный участок — от ближайшей границы; если ближайшая граница выходит в закрытый двор — вход с улицы",
         "трасса входит через наружную стену, а не через двор (ближайшая стена недостижима)",
         base_network() + [
             feat("bld_u", "restriction",
                  B(-40, 100, 40, 170).difference(B(-25, 100, 25, 150)).union(B(-40, 95, 40, 100)),
                  restriction_type="oks"),
             feat("oks_1", "oks_connection_point", P(-32, 130), flow_tph=20.0)])

    case("06_limit_length", "Предельная длина одного ДУ",
         "§2.3: ДУ100 — 419 м, 450 м не проходит → следующий ДУ125 (554 м)",
         "один участок ДУ125, хотя по расходу 20 т/ч хватило бы ДУ100",
         base_network() + [feat("oks_1", "oks_connection_point", P(0, 450), flow_tph=20.0)])

    case("07_school_territory", "Школа внутри своей территории",
         "табл. 2 social_area — запрет 1 м; §2.2 — отступ к содержащим точку полигонам на финальный участок не действует",
         "точка в здании внутри social_area подключена; финальный прямой участок проходит через территорию к ближайшей стене",
         base_network() + [
             feat("school_area", "restriction", B(-70, 60, 70, 200), restriction_type="social_area"),
             feat("school_bld", "restriction", B(-25, 110, 25, 150), restriction_type="oks"),
             feat("oks_1", "oks_connection_point", P(0, 130), flow_tph=20.0)])

    case("08_gas_depth", "Газопровод: проход выше в режиме с глубиной",
         "§5: обычная глубина 3.0, над газом просвет 0.2 (верх газа 2.8) → верх трубы ≤ 2.41 (ДУ100, с запасом 0.01 на округление), уклон 0.1, техузлы на переломах, Kгл=1",
         "5 участков: 3.0 → подъём 5.9 м → 2.41 на спецучастке 4 м → спуск → 3.0; стоимость как в 2D",
         base_network() + [
             feat("gas_1", "restriction", L((-300, 50), (300, 50)), restriction_type="gas_pipeline"),
             feat("oks_1", "oks_connection_point", P(0, 100), flow_tph=20.0)],
         mode="DEPTH_3D")

    case("09_chamber_within_10m", "Правило 10 м до существующей камеры",
         "§2.4: точка присоединения ≤10 м от существующей камеры → используется камера (врезка 5 млн), новая камера не строится",
         "участок заканчивается в ch_1 (start_node_id = ch_1), новых камер 0, existing_chamber_tie_in_count = 1",
         base_network() + [feat("oks_1", "oks_connection_point", P(6, 100), flow_tph=20.0)])

    case("10_unreachable", "Недостижимая точка → штраф, остальные строятся",
         "§2.5, §6: неподключение только если маршрута нет; штраф 100 млн + 500 тыс·G; частичный результат сохраняется",
         "oks_ok подключена; oks_trapped в unconnected_oks_ids, unconnected_penalty = 105 000 000",
         base_network() + [
             feat("water_ring", "restriction", B(150, 150, 250, 250).difference(B(160, 160, 240, 240)), restriction_type="water"),
             feat("oks_trapped", "oks_connection_point", P(200, 200), flow_tph=10.0),
             feat("oks_ok", "oks_connection_point", P(0, 100), flow_tph=20.0)])

    case("11_tram_diagonal", "Трамвай по диагонали: угол ≥45°",
         "табл. 2: трамвайные пути — спецпроход под углом ≥45° к оси, Kспец 1.75, спецзона +3 м",
         "трасса пересекает пути под углом ≥45° (перпендикулярно оси, а не «напрямик»), спецучасток с K=1.75",
         base_network() + [
             feat("tram_1", "restriction",
                  LineString([(OX - 300, OY - 100), (OX + 300, OY + 200)]).buffer(3.0, cap_style=2),
                  restriction_type="tram_tracks"),
             feat("oks_1", "oks_connection_point", P(120, 160), flow_tph=20.0)])

    case("12_two_points_one_building", "Две точки в одном здании",
         "§2.2, FAQ п.4: каждая точка — самостоятельная цель со своим расходом",
         "две ветки, каждая входит в здание через свою ближайшую стену; расход магистрали — сумма",
         base_network() + [
             feat("bld", "restriction", B(-60, 250, 60, 290), restriction_type="oks"),
             feat("oks_a", "oks_connection_point", P(-40, 270), flow_tph=15.0),
             feat("oks_b", "oks_connection_point", P(40, 270), flow_tph=25.0)])

    (OUT / "cases.json").write_text(json.dumps(CASES, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"{len(CASES)} cases -> {OUT}")


if __name__ == "__main__":
    main()
